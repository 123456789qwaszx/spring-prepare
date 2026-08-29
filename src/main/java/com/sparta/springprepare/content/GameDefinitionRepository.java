package com.sparta.springprepare.content;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * game.definition.json 보관. 챕터와 같은 패턴이지만 **색인이 없다** (PLAN M1).
 *
 * 챕터 레포지토리와 코드가 비슷하지만 합치지 않았다. 공통은 checksum 계산뿐이고 그것은 이미 common/Checksum 이다.
 * 두 테이블은 컬럼도 규칙도 다르며(색인 유무, 버전 범위), 억지로 추상화하면 나중에 한쪽만 바뀔 때 둘 다 흔들린다.
 * "중복처럼 보이지만 이유가 다른 코드"는 합치지 않는다.
 */
@Repository
public class GameDefinitionRepository {

    private final JdbcClient jdbc;

    public GameDefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_VERSION_BY_CHECKSUM = """
            SELECT version FROM game_definitions WHERE checksum = :checksum
            """;

    /** checksum 컬럼은 V2 마이그레이션으로 추가됐다 (D-007). */
    public Optional<Integer> findVersionByChecksum(String checksum) {
        return jdbc.sql(SELECT_VERSION_BY_CHECKSUM)
                .param("checksum", checksum)
                .query(Integer.class)
                .optional();
    }

    private static final String NEXT_VERSION = """
            SELECT COALESCE(MAX(version), 0) + 1 FROM game_definitions
            """;

    /** definition 은 챕터와 달리 전역으로 하나의 버전 계열이다 — WHERE 절이 없다. */
    public int nextVersion() {
        return jdbc.sql(NEXT_VERSION).query(Integer.class).single();
    }

    private static final String INSERT = """
            INSERT INTO game_definitions (version, body, checksum)
            VALUES (:version, :body, :checksum)
            """;

    public void insert(int version, String body, String checksum) {
        jdbc.sql(INSERT)
                .param("version", version)
                .param("body", body)
                .param("checksum", checksum)
                .update();   // 생성 키가 필요 없다 — version 을 이미 알고 있다
    }

    private static final String SELECT_BODY = """
            SELECT body FROM game_definitions WHERE version = :version
            """;

    public Optional<String> findBody(int version) {
        return jdbc.sql(SELECT_BODY).param("version", version).query(String.class).optional();
    }

    private static final String SELECT_LATEST_BODY = """
            SELECT body FROM game_definitions ORDER BY version DESC LIMIT 1
            """;

    public Optional<String> findLatestBody() {
        return jdbc.sql(SELECT_LATEST_BODY).query(String.class).optional();
    }
}