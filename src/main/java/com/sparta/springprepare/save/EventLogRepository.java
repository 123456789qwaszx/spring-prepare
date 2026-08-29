package com.sparta.springprepare.save;

import com.sparta.springprepare.common.UtcTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * event_log — [1] 영구 계층의 실체. EventKey 가 붙은 에피소드를 다 보면 한 행.
 *
 * 챕터 해금·엔딩 통계는 전부 이 테이블에 대한 쿼리다 (schema.sql 주석).
 * 다만 **해금을 판정하는 것은 서버가 아니다** — 클라가 game.definition.json 의 규칙으로 판정하고,
 * 서버는 "무엇이 일어났는가" 만 기록한다.
 */
@Repository
public class EventLogRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public EventLogRepository(JdbcClient jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    private static final String INSERT = """
            INSERT INTO event_log
                (playthrough_id, event_key, chapter_content_id, episode_id, occurred_at)
            VALUES
                (:playthroughId, :eventKey, :chapterContentId, :episodeId, :occurredAt)
            """;

    /**
     * @param eventKeyByEpisode 에피소드 → EventKey. 클라가 보낸 값이 아니라
     *                          서버가 chapter_episodes 에서 찾은 값이다 (EventUpload 주석 참조).
     *
     * uk_event_once UNIQUE 를 위반하면(같은 이벤트 재기록) DuplicateKeyException → 409.
     * M4 까지는 그대로 두고, 그 뒤에 재전송 흡수로 바꾼다.
     */
    public void insertAll(long playthroughId, long chapterContentId,
                          List<EventUpload> events, Map<String, String> eventKeyByEpisode) {
        if (events.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("playthroughId", playthroughId)
                        .addValue("eventKey", eventKeyByEpisode.get(e.episodeId()))
                        .addValue("chapterContentId", chapterContentId)
                        .addValue("episodeId", e.episodeId())
                        .addValue("occurredAt", UtcTime.toDbValue(e.occurredAt())))
                .toArray(SqlParameterSource[]::new);
        namedJdbc.batchUpdate(INSERT, batch);
    }

    // 첫 JOIN. event_log 는 chapter_content_id 라는 숫자만 들고 있고,
    // 그것이 어느 챕터의 몇 번째 버전인지는 chapter_contents 가 안다.
    // FK 가 있으므로 매칭되지 않는 행은 존재할 수 없다 → INNER JOIN 이 맞다.
    // (여기서 행이 빠진다면 LEFT JOIN 으로 감출 게 아니라 데이터가 깨진 것이다.)
    private static final String SELECT_BY_PLAYTHROUGH = """
            SELECT e.event_key,
                   c.chapter_id,
                   c.version      AS chapter_version,
                   c.display_name AS chapter_display_name,
                   e.episode_id,
                   e.occurred_at
            FROM event_log e
            JOIN chapter_contents c ON c.id = e.chapter_content_id
            WHERE e.playthrough_id = :playthroughId
            ORDER BY e.occurred_at, e.id
            """;

    public List<EventLogItem> findByPlaythrough(long playthroughId) {
        return jdbc.sql(SELECT_BY_PLAYTHROUGH)
                .param("playthroughId", playthroughId)
                .query(EventLogItem.class)
                .list();
    }
}
