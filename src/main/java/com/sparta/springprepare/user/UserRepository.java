package com.sparta.springprepare.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/**
 * users 테이블 접근. SQL 은 문자열 상수로 메서드 바로 위에 둔다 — 숨기지 않는다 (PLAN §2.4).
 *
 * JdbcClient 는 Boot 가 DataSource 로 자동 등록해 준다 (JdbcClientAutoConfiguration).
 * 트랜잭션 경계는 여기 두지 않는다. Service 가 연다 (PLAN §2.5).
 */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // created_at 을 INSERT 에서 빼야 DB 의 DEFAULT CURRENT_TIMESTAMP 가 들어간다.
    // 앱이 LocalDateTime.now() 를 넣으면 앱 서버 시각이 되고, 서버가 두 대가 되는 순간 시각이 갈린다.
    private static final String INSERT = """
            INSERT INTO users (username, password)
            VALUES (:username, :password)
            """;

    /**
     * @return DB 가 발급한 AUTO_INCREMENT id.
     * id 를 앱에서 세지 않는다 (실습1·2의 Collections.max(keySet)+1 은 여기서 끝난다).
     * 같은 username 이면 DB 의 UNIQUE 가 막고 DuplicateKeyException 이 올라온다 — 여기서 잡지 않는다.
     */
    public long insert(String username, String password) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("username", username)
                .param("password", password)
                .update(keyHolder);   // update() 가 아니라 update(keyHolder): RETURN_GENERATED_KEYS 로 실행된다
        return Objects.requireNonNull(keyHolder.getKey(), "users INSERT 가 생성 키를 돌려주지 않았다").longValue();
    }

    // SELECT * 를 쓰지 않는 이유: 컬럼이 늘어도 이 쿼리가 무엇을 읽는지 코드에서 보이게.
    private static final String SELECT_BY_ID = """
            SELECT id, username, password, created_at
            FROM users
            WHERE id = :id
            """;

    /** 없으면 Optional.empty(). "없음"을 예외로 만들지 말지는 Service 가 정한다. */
    public Optional<User> findById(long id) {
        return jdbc.sql(SELECT_BY_ID)
                .param("id", id)
                .query(User.class)   // record 생성자 파라미터명 ↔ 컬럼명(snake_case 자동 변환) 매핑
                .optional();
    }

    // EXISTS 는 첫 행을 찾는 순간 멈춘다. COUNT(*) 는 전부 세므로 "있는지만" 알고 싶을 때는 낭비다.
    // 지금 규모에선 차이가 없지만, 쿼리가 무엇을 요구하는지 정확히 쓰는 습관이다.
    private static final String EXISTS_BY_ID = """
            SELECT EXISTS(SELECT 1 FROM users WHERE id = :id)
            """;

    /** M2 이후 다른 패키지(playthrough)가 "이 사용자가 있는가"를 물을 때 쓴다. 행 전체를 읽을 필요가 없다. */
    public boolean existsById(long id) {
        return Boolean.TRUE.equals(
                jdbc.sql(EXISTS_BY_ID).param("id", id).query(Boolean.class).single());
    }
}