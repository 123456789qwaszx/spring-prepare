package com.sparta.springprepare.playthrough;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PlaythroughRepository {

    private final JdbcClient jdbc;

    public PlaythroughRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * started_at 은 DB DEFAULT. ended_at 은 NULL 로 시작한다 = 진행 중.
     *
     * <p>M8-A 부터 클라 신원(client_id)과 갈래(forked_*)를 함께 넣는다. forked_from_id 는 부모가 서버에
     * 있을 때만 값이 있고(서비스가 클라 id 로 찾아 넘긴다), 없으면 NULL 로 두었다가 부모가 오면
     * {@link #backfillChildren} 이 채운다 (D-020).
     */
    private static final String INSERT = """
            INSERT INTO playthroughs
                (user_id, client_id, forked_from_id, forked_from_client_id, forked_scene_index)
            VALUES
                (:userId, :clientId, :forkedFromId, :forkedFromClientId, :forkedSceneIndex)
            """;

    public long insert(long userId, String clientId,
                       Long forkedFromId, String forkedFromClientId, Integer forkedSceneIndex) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("userId", userId)
                .param("clientId", clientId)
                .param("forkedFromId", forkedFromId)
                .param("forkedFromClientId", forkedFromClientId)
                .param("forkedSceneIndex", forkedSceneIndex)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "playthroughs INSERT 가 생성 키를 돌려주지 않았다")
                .longValue();
    }

    /** M0~M7 의 본문 없는 생성 — 테스트 픽스처와 seed 가 이 모양의 행을 남긴다. 앱 경로에서는 더 쓰지 않는다. */
    public long insert(long userId) {
        return insert(userId, null, null, null, null);
    }

    /**
     * 멱등 키 조회 (D-019). 같은 사용자의 같은 클라 id 는 하나뿐이다 — UNIQUE (user_id, client_id).
     * 사용자로 한정하는 이유: 다른 사용자가 우연히 같은 guid 를 보내도(사실상 불가능하지만) 남의 회차가 안 잡힌다.
     */
    private static final String SELECT_ID_BY_CLIENT = """
            SELECT id FROM playthroughs WHERE user_id = :userId AND client_id = :clientId
            """;

    public Optional<Long> findIdByClientId(long userId, String clientId) {
        return jdbc.sql(SELECT_ID_BY_CLIENT)
                .param("userId", userId)
                .param("clientId", clientId)
                .query(Long.class)
                .optional();
    }

    /**
     * 자식 되채우기 (D-020). 이 회차가 생기기 전에 "이 클라 id 를 부모로" 적고 온 갈래들의 forked_from_id 를
     * 지금 채운다. 도착 순서가 어떻든 그래프가 스스로 닫히는 한 문장이다.
     * 영향 행 수는 정보일 뿐이다 — 0 이 정상(부모보다 자식이 먼저 온 적이 없다)이고 판정에 쓰지 않는다.
     */
    private static final String BACKFILL_CHILDREN = """
            UPDATE playthroughs
            SET forked_from_id = :parentId
            WHERE user_id = :userId
              AND forked_from_client_id = :parentClientId
              AND forked_from_id IS NULL
            """;

    public int backfillChildren(long userId, String parentClientId, long parentId) {
        return jdbc.sql(BACKFILL_CHILDREN)
                .param("userId", userId)
                .param("parentClientId", parentClientId)
                .param("parentId", parentId)
                .update();
    }

    private static final String SELECT_BY_ID = """
            SELECT id, user_id, started_at, ended_at
            FROM playthroughs
            WHERE id = :id
            """;

    public Optional<Playthrough> findById(long id) {
        return jdbc.sql(SELECT_BY_ID).param("id", id).query(Playthrough.class).optional();
    }

    /**
     * 회차 목록 (M2 + M8-A 확장, 핸드오프 R4). 이력 화면이 스냅샷 없이 그릴 수 있는 것 전부.
     *
     * <p>팬아웃을 피하는 세 가지 (M5 user_summary 의 교훈):
     * <ul>
     *   <li>슬롯은 {@code slot_no = 1} 하나만 LEFT JOIN — 회차당 슬롯 하나가 클라의 규약(R5)이고,
     *       그래야 회차 한 줄이 한 행이다. 슬롯이 없는 회차도 남아야 하므로 LEFT.</li>
     *   <li>슬롯 수·즐겨찾기 수는 상관 서브쿼리 — 조인하면 행이 곱해진다.</li>
     *   <li>즐겨찾기는 {@code playthrough_client_id} 로 센다 — 서버 id 링크는 해석 전이면 NULL 이지만
     *       클라 id 는 항상 있다. 삭제된 것은 뺀다.</li>
     * </ul>
     *
     * <p>중첩 record(ForkOrigin)가 있어 {@code query(Class)} 의 자동 매핑 대신 RowMapper 로 조립한다 —
     * 이 레포에서 RowMapper 를 쓰는 첫 자리다. 평평한 컬럼 → 중첩 객체는 손으로 접는 수밖에 없다.
     */
    private static final String SELECT_SUMMARIES = """
            SELECT p.id,
                   p.client_id,
                   p.forked_from_id,
                   p.forked_from_client_id,
                   p.forked_scene_index,
                   p.started_at,
                   p.ended_at,
                   (SELECT COUNT(*) FROM save_slots s2 WHERE s2.playthrough_id = p.id) AS slot_count,
                   c.chapter_id,
                   c.version                 AS chapter_version,
                   s.current_episode_id,
                   s.chapter_completed,
                   s.inherited_play_seconds,
                   s.own_play_seconds,
                   s.play_seconds,
                   (SELECT COUNT(*) FROM bookmarks b
                     WHERE b.user_id = p.user_id
                       AND b.playthrough_client_id = p.client_id
                       AND b.deleted_at IS NULL) AS bookmark_count,
                   s.updated_at              AS last_saved_at
            FROM playthroughs p
            LEFT JOIN save_slots       s ON s.playthrough_id = p.id AND s.slot_no = 1
            LEFT JOIN chapter_contents c ON c.id = s.chapter_content_id
            WHERE p.user_id = :userId
            ORDER BY p.id
            """;

    private static final RowMapper<PlaythroughSummary> SUMMARY_MAPPER = PlaythroughRepository::mapSummary;

    private static PlaythroughSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        String forkedFromClientId = rs.getString("forked_from_client_id");
        ForkOrigin forkedFrom = forkedFromClientId == null
                ? null
                : new ForkOrigin(
                        rs.getObject("forked_from_id", Long.class),
                        forkedFromClientId,
                        rs.getObject("forked_scene_index", Integer.class));

        return new PlaythroughSummary(
                rs.getLong("id"),
                rs.getString("client_id"),
                forkedFrom,
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getInt("slot_count"),
                rs.getString("chapter_id"),
                rs.getObject("chapter_version", Integer.class),
                rs.getString("current_episode_id"),
                rs.getObject("chapter_completed", Boolean.class),
                rs.getObject("inherited_play_seconds", Integer.class),
                rs.getObject("own_play_seconds", Integer.class),
                rs.getObject("play_seconds", Integer.class),
                rs.getInt("bookmark_count"),
                rs.getObject("last_saved_at", OffsetDateTime.class));
    }

    public List<PlaythroughSummary> findSummariesByUser(long userId) {
        return jdbc.sql(SELECT_SUMMARIES)
                .param("userId", userId)
                .query(SUMMARY_MAPPER)
                .list();
    }

    // ended_at IS NULL 조건이 멱등성을 만든다 — 이미 끝난 회차를 다시 끝내도 시각이 덮이지 않는다.
    // 영향 행 수가 0이라고 실패가 아니다 ("이미 끝나 있었다"도 0이다). 판정은 Service 가 조회로 한다.
    private static final String END = """
            UPDATE playthroughs
            SET ended_at = CURRENT_TIMESTAMP
            WHERE id = :id AND ended_at IS NULL
            """;

    public void end(long id) {
        jdbc.sql(END).param("id", id).update();
    }
}
