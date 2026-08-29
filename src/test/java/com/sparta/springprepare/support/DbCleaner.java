package com.sparta.springprepare.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * 통합 테스트 전에 테이블을 비운다 (PLAN §2.6).
 *
 * TRUNCATE 가 아니라 DELETE 인 이유 (ANALYSIS §3.5):
 * MySQL 은 FK 로 참조되는 부모 테이블에 TRUNCATE 를 거부한다 — 자식이 비어 있어도.
 * SET FOREIGN_KEY_CHECKS=0 은 세션 변수라 커넥션 풀에서 같은 커넥션을 보장해야 하고 TRUNCATE 는 암묵 커밋을 일으킨다.
 * DELETE 는 느리지만 테스트 규모에선 차이가 없고, 자식 → 부모 순서를 코드에 적어 두면 FK 방향이 그대로 드러난다.
 *
 * 마일스톤마다 테이블이 늘면 이 목록만 갱신한다. 순서 규칙: 참조하는 쪽(자식)이 먼저.
 * Spring 빈이 아니라 평범한 클래스다 — 테스트에서 new 로 만든다. 테스트 지원 코드에 컴포넌트 스캔을 태우지 않는다.
 */
public final class DbCleaner {

    // 자식 → 부모. 지금은 M0 범위. M1 에서 chapter_episodes, chapter_contents, game_definitions 가 앞에 붙는다.
    private static final List<String> TABLES_CHILD_FIRST = List.of(
            "devices",       // → users
            "users"
    );

    private final JdbcClient jdbc;

    public DbCleaner(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        for (String table : TABLES_CHILD_FIRST) {
            // 테이블명은 상수 목록에서만 오므로 문자열 결합이 안전하다. 바인딩 파라미터는 식별자에 쓸 수 없다.
            jdbc.sql("DELETE FROM " + table).update();
        }
    }
}
