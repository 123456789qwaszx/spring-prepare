package com.sparta.springprepare.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/**
 * DB 접근 계층
 * - DB에서 어떻게 데이터를 읽고 쓸지만 담당.
 * (SQL 은 문자열 상수로 메서드 바로 위에 둠 - 학습용.)
 */
@Repository
public class UserRepository {

    /**
     * Java 코드와 JDBC/DB 사이를 연결해주는 도구
     * UserRepository -> JdbcClient -> JDBC -> DataSource(SpringBoot) -> MySQL
     * */
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // created_at 을 INSERT 에서 빼서, DB 의 DEFAULT CURRENT_TIMESTAMP을 넣음.
    // 데이터 생성 시각의 책임을 DB에 두기 위함.
    private static final String INSERT = """
            INSERT INTO users (username, password)
            VALUES (:username, :password)
            """;

    /**
     * - username과 password를 받아 users에 INSERT하고, DB가 생성한 id를 돌려줌.
     * (@return AUTO_INCREMENT id.(DB 발급))
     * ID 생성 책임을 애플리케이션이 아닌 DB(ID 충돌 방지.)
     */
    public long insert(String username, String password) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        // 실제 SQL 실행 및 생성된 ID 회수.
        jdbc.sql(INSERT) // SQL 선택
                .param("username", username) // parameter 연결
                .param("password", password)
                .update(keyHolder);   // "INSERT하면서 DB가 생성한 generated key도 받음
        return Objects.requireNonNull(keyHolder.getKey(), "users INSERT 가 생성 키를 돌려주지 않았다").longValue();
    }

    // SELECT * 를 쓰지 않는 이유: 컬럼이 늘어도 이 쿼리가 무엇을 읽는지 코드에서 보이게.
    private static final String SELECT_BY_ID = """
            SELECT id, username, password, created_at
            FROM users
            WHERE id = :id
            """;

    /** 없으면 Optional.empty(). "없음"에 대한 예외 처리 정책은 Service 책임. */
    public Optional<User> findById(long id) {
        return jdbc.sql(SELECT_BY_ID)
                .param("id", id)
                .query(User.class)   // record 생성자 파라미터명 ↔ 컬럼명(snake_case 자동 변환) 매핑
                .optional();
    }
}