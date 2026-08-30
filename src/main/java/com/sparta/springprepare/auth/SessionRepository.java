package com.sparta.springprepare.auth;

import com.sparta.springprepare.common.UtcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * sessions 테이블 접근 (M6-4). SQL 은 문자열 상수로 메서드 바로 위에 — 숨기지 않는다 (PLAN §2.4).
 *
 * 생성 키가 없다 — token 이 PK 이고 그 값은 앱(AuthService)이 만든다.
 * AUTO_INCREMENT 를 쓰지 않는 첫 테이블이다: "DB 가 번호를 발급한다"는 규칙은
 * **의미 없는 식별자**에 대한 것이고, 토큰은 값 자체가 비밀이라 앱이 만드는 것이 맞다.
 */
@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // created_at 은 DB DEFAULT (M0 규칙). expires_at 은 앱이 UTC 로 정규화해 넣는다 (D-009).
    private static final String INSERT = """
            INSERT INTO sessions (token, user_id, expires_at)
            VALUES (:token, :userId, :expiresAt)
            """;

    public void insert(String token, long userId, OffsetDateTime expiresAt) {
        jdbc.sql(INSERT)
                .param("token", token)
                .param("userId", userId)
                .param("expiresAt", UtcTime.toDbValue(expiresAt))
                .update();
    }

    private static final String SELECT_BY_TOKEN = """
            SELECT token, user_id, expires_at
            FROM sessions
            WHERE token = :token
            """;

    /**
     * 만료 여부를 SQL(WHERE expires_at > UTC_TIMESTAMP())로 거르지 않고 행을 그대로 돌려준다.
     * 판정을 앱(인터셉터)에 두면 "없는 토큰"과 "만료된 토큰"을 구분해 로그에 남길 수 있고,
     * 시각 비교가 어느 시계(DB vs 앱)로 일어나는지가 코드에 보인다.
     */
    public Optional<Session> findByToken(String token) {
        return jdbc.sql(SELECT_BY_TOKEN)
                .param("token", token)
                .query(Session.class)
                .optional();
    }

    private static final String DELETE = """
            DELETE FROM sessions WHERE token = :token
            """;

    /**
     * @return 지운 행 수. 0 이어도 오류가 아니다 — 이미 로그아웃됐거나 만료 전에 지워진 것이고,
     *         "로그아웃" 요청의 목적(그 토큰이 더는 유효하지 않음)은 어느 쪽이든 달성돼 있다.
     */
    public int deleteByToken(String token) {
        return jdbc.sql(DELETE).param("token", token).update();
    }
}
