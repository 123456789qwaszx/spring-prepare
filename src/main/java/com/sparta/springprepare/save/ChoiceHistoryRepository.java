package com.sparta.springprepare.save;

import com.sparta.springprepare.common.UtcTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * choice_history — 선택 이력. 슬롯 하나에 seq 순으로 쌓인다.
 *
 * 스냅샷(upsert, 덮어쓰기)과 달리 이력은 **INSERT 만** 한다. 지나간 선택은 바뀌지 않는다.
 */
@Repository
public class ChoiceHistoryRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public ChoiceHistoryRepository(JdbcClient jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    // received_at 은 넣지 않는다 — DB DEFAULT 가 서버 시각을 채운다.
    // chosen_at 은 클라 시각이므로 서버가 덮지 않는다. 두 컬럼이 따로 있는 이유다.
    private static final String INSERT = """
            INSERT INTO choice_history
                (save_slot_id, seq, chapter_content_id, episode_id, option_index, chosen_at)
            VALUES
                (:saveSlotId, :seq, :chapterContentId, :episodeId, :optionIndex, :chosenAt)
            """;

    /**
     * 배치 INSERT. 도구는 M1 의 색인 INSERT 와 같다 (JdbcClient 는 배치를 지원하지 않는다).
     *
     * chosenAt 을 UtcTime.toDbValue 로 LocalDateTime 으로 바꿔 넘기는 것이 D-009 의 핵심이다.
     * OffsetDateTime 을 그대로 넘기면 Connector/J 는 DATETIME 대상에 **변환 없이** 벽시계 부분만 넣는다 —
     * 클라가 +09:00 으로 보내면 9시간 어긋난 값이 조용히 저장된다.
     */
    public void insertAll(long saveSlotId, long chapterContentId, List<ChoiceUpload> choices) {
        if (choices.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = choices.stream()
                .map(c -> new MapSqlParameterSource()
                        .addValue("saveSlotId", saveSlotId)
                        .addValue("seq", c.seq())
                        .addValue("chapterContentId", chapterContentId)
                        .addValue("episodeId", c.episodeId())
                        .addValue("optionIndex", c.optionIndex())
                        .addValue("chosenAt", UtcTime.toDbValue(c.chosenAt())))
                .toArray(SqlParameterSource[]::new);
        namedJdbc.batchUpdate(INSERT, batch);
    }

    // afterSeq 는 "이 번호 다음부터" 다. 클라가 마지막으로 받은 seq 를 주면 증분만 돌아온다.
    // 기본값 0 이면 전부 — seq 는 1부터 시작한다고 가정하는 것이 아니라, 0 보다 큰 것을 전부 준다는 뜻이다.
    private static final String SELECT_AFTER = """
            SELECT seq, episode_id, option_index, chosen_at
            FROM choice_history
            WHERE save_slot_id = :saveSlotId AND seq > :afterSeq
            ORDER BY seq
            """;

    public List<ChoiceHistoryItem> findAfter(long saveSlotId, int afterSeq) {
        return jdbc.sql(SELECT_AFTER)
                .param("saveSlotId", saveSlotId)
                .param("afterSeq", afterSeq)
                .query(ChoiceHistoryItem.class)
                .list();
    }

    // M4 가 두 가지에 쓴다. 같은 조회로 두 질문에 답한다 (M3 의 findByIds 와 같은 방식).
    //  - 재전송 판정: 보낸 seq 가 **전부** 이미 있으면 "내가 방금 보낸 그 요청"이다 (D-010)
    //  - force: 이미 있는 것을 빼고 **새 것만** INSERT 한다
    private static final String SELECT_EXISTING_SEQS = """
            SELECT seq
            FROM choice_history
            WHERE save_slot_id = :saveSlotId AND seq IN (:seqs)
            """;

    /**
     * force 에서 INSERT IGNORE 를 쓰지 않는 이유가 여기 있다.
     * {@code INSERT IGNORE} 는 UNIQUE 위반만이 아니라 **FK 위반·타입 오류까지 경고로 바꿔 삼킨다** —
     * 없는 에피소드를 가리키는 선택이 조용히 사라지고 아무도 모른다.
     * 무엇을 무시할지 고르지 못하는 도구는, 무시하면 안 되는 것도 무시한다.
     */
    public Set<Integer> existingSeqs(long saveSlotId, Collection<Integer> seqs) {
        if (seqs.isEmpty()) {
            return Set.of();     // IN () 은 SQL 문법 오류다
        }
        return new LinkedHashSet<>(jdbc.sql(SELECT_EXISTING_SEQS)
                .param("saveSlotId", saveSlotId)
                .param("seqs", seqs)
                .query(Integer.class)
                .list());
    }
}
