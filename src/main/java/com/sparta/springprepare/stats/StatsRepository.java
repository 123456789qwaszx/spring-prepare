package com.sparta.springprepare.stats;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 집계 조회 (PLAN M5). <b>이 레포지토리만 SQL 을 파일에서 읽는다.</b>
 *
 * <h3>왜 여기만 다른가</h3>
 * M0~M4 의 SQL 은 짧고 그 메서드와 한 몸이라 상수로 두는 편이 읽기 좋았다 — 메서드를 보면 쿼리가 같이 보인다.
 * M5 의 쿼리는 30~50줄이고, 무엇보다 <b>Workbench 에서 그대로 돌려 봐야 한다.</b>
 * 집계는 "먼저 SQL 로 숫자를 맞춘 뒤 앱에 붙이는" 순서로 만들기 때문이다 (M5 계획서 §3-2).
 *
 * 파일로 빼면 앱과 Workbench 가 <b>같은 텍스트</b>를 쓴다. 복사해 옮기다 어긋날 자리가 없어진다 —
 * 숫자가 다르면 원인은 쿼리가 아니라 다른 데 있다는 것이 확실해진다.
 *
 * <h3>대가와 그 처리</h3>
 * 잃는 것은 <b>컴파일 시점의 보장</b>이다. 상수는 파일이 없을 수가 없지만, 클래스패스 리소스는
 * 오타 하나로 런타임에 사라진다. 그래서 <b>생성자에서 미리 읽는다</b> —
 * 파일이 없으면 첫 요청이 아니라 <b>기동이 실패</b>한다. 늦게 아는 것보다 일찍 죽는 편이 낫다.
 * (요청마다 파일을 읽지 않게 되는 것은 곁가지 이득이다.)
 *
 * <h3>매핑</h3>
 * SQL 의 별칭을 camelCase 로 둔 이유: {@code SimplePropertyRowMapper} 는 컬럼명과 record 컴포넌트명을
 * 소문자로 눕혀 비교하므로 {@code eventKey} 도 {@code event_key} 도 맞는다.
 * 별칭 쪽을 record 와 같은 모양으로 두면 <b>SQL 만 봐도 어떤 DTO 가 나오는지</b> 읽힌다.
 */
@Repository
public class StatsRepository {

    private final JdbcClient jdbc;

    private final String eventReachSql;
    private final String choiceRatioSql;
    private final String userSummarySql;
    private final String chapterOverviewSql;

    public StatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.eventReachSql = load("sql/stats/event_reach.sql");
        this.choiceRatioSql = load("sql/stats/choice_ratio.sql");
        this.userSummarySql = load("sql/stats/user_summary.sql");
        this.chapterOverviewSql = load("sql/stats/chapter_overview.sql");
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 기동 실패로 이어진다 — 의도한 것이다 (위 주석 참조).
            throw new IllegalStateException("SQL 파일을 읽지 못했습니다: " + path, e);
        }
    }

    /** 파라미터가 없다. 그래서 Workbench 에 그대로 붙여넣어도 돈다. */
    public List<EventReachItem> eventReach() {
        return jdbc.sql(eventReachSql)
                .query(EventReachItem.class)
                .list();
    }

    public List<ChoiceRatioItem> choiceRatio(String chapterId, int version) {
        return jdbc.sql(choiceRatioSql)
                .param("chapterId", chapterId)
                .param("version", version)
                .query(ChoiceRatioItem.class)
                .list();
    }

    /** 사용자가 없으면 0행이다 (WHERE u.id = :userId). 서비스가 404 로 번역한다. */
    public Optional<ChapterOverview> chapterOverview(String chapterId, int version) {
        return jdbc.sql(chapterOverviewSql)
                .param("chapterId", chapterId)
                .param("version", version)
                .query(ChapterOverview.class)
                .optional();
    }

    public Optional<UserSummary> userSummary(long userId) {
        return jdbc.sql(userSummarySql)
                .param("userId", userId)
                .query(UserSummary.class)
                .optional();
    }
}
