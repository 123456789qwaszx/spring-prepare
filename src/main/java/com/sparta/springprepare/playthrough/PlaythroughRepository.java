package com.sparta.springprepare.playthrough;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PlaythroughRepository {

    private final JdbcClient jdbc;

    public PlaythroughRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // started_at 은 DB DEFAULT. ended_at 은 NULL 로 시작한다 = 진행 중.
    private static final String INSERT = """
            INSERT INTO playthroughs (user_id) VALUES (:userId)
            """;

    public long insert(long userId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(INSERT).param("userId", userId).update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "playthroughs INSERT 가 생성 키를 돌려주지 않았다")
                .longValue();
    }

    private static final String SELECT_BY_ID = """
            SELECT id, user_id, started_at, ended_at
            FROM playthroughs
            WHERE id = :id
            """;

    public Optional<Playthrough> findById(long id) {
        return jdbc.sql(SELECT_BY_ID).param("id", id).query(Playthrough.class).optional();
    }

    // 상관 서브쿼리로 슬롯 수를 센다.
    // save_slots 를 LEFT JOIN + GROUP BY 로 세도 되지만, 그러면 회차마다 GROUP BY 가 필요하고
    // 나중에 다른 집계를 더할 때 쿼리가 복잡해진다. 여기서는 세는 대상이 하나뿐이라 서브쿼리가 읽기 쉽다.
    private static final String SELECT_SUMMARIES = """
            SELECT p.id,
                   p.started_at,
                   p.ended_at,
                   (SELECT COUNT(*) FROM save_slots s WHERE s.playthrough_id = p.id) AS slot_count
            FROM playthroughs p
            WHERE p.user_id = :userId
            ORDER BY p.id
            """;

    public List<PlaythroughSummary> findSummariesByUser(long userId) {
        return jdbc.sql(SELECT_SUMMARIES)
                .param("userId", userId)
                .query(PlaythroughSummary.class)
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