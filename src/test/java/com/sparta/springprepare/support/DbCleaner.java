package com.sparta.springprepare.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * 통합 테스트 전에 테이블을 비운다 (PLAN §2.6).
 *
 * TRUNCATE 가 아니라 DELETE 인 이유 (ANALYSIS §3.5):
 * MySQL 은 FK 로 참조되는 부모 테이블에 TRUNCATE 를 거부한다 — 자식이 비어 있어도.
 * DELETE 는 느리지만 테스트 규모에선 차이가 없고, 자식 → 부모 순서를 코드에 적어 두면 FK 방향이 그대로 드러난다.
 *
 * 마일스톤마다 테이블이 늘면 이 목록만 갱신한다. 순서 규칙: 참조하는 쪽(자식)이 먼저.
 */
public final class DbCleaner {

    /**
     * 자식 → 부모. M2 까지의 전 테이블.
     *
     * 이 순서가 곧 FK 그래프의 위상 정렬이다. 확인해 보면:
     *   choice_history → save_slots, chapter_episodes
     *   event_log      → playthroughs, chapter_episodes
     *   save_slots     → playthroughs, chapter_contents, devices
     *   playthroughs   → users
     *   chapter_episodes → chapter_contents
     *   devices        → users
     * 화살표의 오른쪽이 항상 왼쪽보다 뒤에 온다.
     *
     * 특히 devices 가 users 바로 앞이 아니라 save_slots 뒤에 있는 것에 주의 —
     * save_slots.device_id 가 devices 를 참조하므로 devices 를 먼저 지우면 실패한다.
     */
    private static final List<String> TABLES_CHILD_FIRST = List.of(
            "choice_history",
            "event_log",
            "save_slots",
            "playthroughs",
            "chapter_episodes",
            "chapter_contents",
            "game_definitions",
            "devices",
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
