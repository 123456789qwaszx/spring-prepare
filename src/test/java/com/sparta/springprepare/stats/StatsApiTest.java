package com.sparta.springprepare.stats;

import com.sparta.springprepare.support.AuthSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 집계 API (PLAN M5 완료 기준 + docs/plans/M5.md §7).
 *
 * <h3>여기 하드코딩된 숫자는 어디서 왔나</h3>
 * <b>`db/seed.sql` 의 설계표에서 왔고, Workbench 에서 손으로 확인한 값이다.</b>
 * 쿼리를 돌려 나온 값을 그대로 옮겨 적으면 테스트가 아무것도 지키지 않는다 —
 * 쿼리가 틀려도 "틀린 값과 같다" 를 통과시킬 뿐이다.
 * 그래서 M5 의 순서가 <b>seed 설계 → Workbench 검산 → 앱 → 테스트</b> 다 (M5 계획서 §3-2).
 *
 * <h3>seed 를 어떻게 넣나</h3>
 * `db/seed.sql` 은 클래스패스가 아니라 프로젝트 루트에 있다 (`db/migrations/` 와 같은 자리, PLAN §3).
 * 그래서 `file:` 접두사로 읽는다 — Gradle 의 test 작업 디렉터리가 프로젝트 루트이기 때문이다.
 *
 * seed 가 스스로 전부 지우고 시작하므로 `DbCleaner` 를 쓰지 않는다. 이 클래스는
 * <b>읽기만</b> 하므로 테스트끼리 간섭하지도 않는다.
 *
 * <h3>encoding = "UTF-8" 이 왜 필요한가</h3>
 * {@code @SqlConfig} 의 encoding 기본값은 <b>플랫폼 기본 인코딩</b>이다.
 * 한국어 Windows 에서는 MS949 라서, UTF-8 로 저장된 seed 를 MS949 로 읽어
 * `성실하게 간다` 가 `?꽦?떎?븯寃?` 로 DB 에 들어간다.
 *
 * SQL 문법은 멀쩡하고 행 수도 맞으므로 <b>숫자를 보는 테스트는 전부 통과한다.</b>
 * 라벨을 단언하는 테스트만 깨진다 — M3 의 PowerShell 한글 깨짐과 같은 종류다.
 * <b>"서버가 깨뜨린 것이 아니라 읽는 쪽이 잘못 해석한 것"</b> 이고, 고칠 자리도 읽는 쪽이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "file:db/seed.sql", config = @SqlConfig(encoding = "UTF-8"))
class StatsApiTest {

    @Autowired
    MockMvc mockMvc;

    /** /stats/** 는 관리자 키가 지킨다 (D-013). 값은 프로필 설정에서 읽는다 — 테스트가 값에 묶이지 않게. */
    @Value("${app.admin-key}")
    String adminKey;

    /** /users/{id}/summary 는 본인 토큰이 필요하다. seed 사용자의 비밀번호는 전부 'seed-only' (M6-3b). */
    private String amiyaBearer;   // id 1
    private String eyjaBearer;    // id 5

    @BeforeEach
    void loginSeedUsers() throws Exception {
        // @Sql(seed)이 이 메서드보다 먼저 돈다 — SpringExtension 의 리스너가 @BeforeEach 보다 앞이다.
        // seed 가 sessions 도 비우므로 매 테스트 새로 로그인한다.
        amiyaBearer = AuthSupport.login(mockMvc, "amiya", "seed-only");
        eyjaBearer = AuthSupport.login(mockMvc, "eyja", "seed-only");
    }

    // ── 이벤트 도달률 ────────────────────────────────────────────────

    @Test
    void 이벤트_도달률은_전체_회차를_분모로_한다() throws Exception {
        // 분모 20 = 전체 회차 (종료 여부 무관). 이 정의를 바꾸면 답이 바뀐다 —
        // 종료 회차 12 를 분모로 하면 ENDING_A 는 66.7% 다. 둘 다 맞는 숫자이고 물음이 다르다.
        mockMvc.perform(get("/stats/events").header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                // ORDER BY reachedPlaythroughs DESC
                .andExpect(jsonPath("$[0].eventKey").value("MILESTONE_MIDPOINT"))
                .andExpect(jsonPath("$[0].reachedPlaythroughs").value(15))
                .andExpect(jsonPath("$[0].totalPlaythroughs").value(20))
                .andExpect(jsonPath("$[0].reachRate").value(75.0))
                .andExpect(jsonPath("$[1].eventKey").value("ENDING_A"))
                .andExpect(jsonPath("$[1].reachedPlaythroughs").value(8))
                .andExpect(jsonPath("$[1].reachRate").value(40.0))
                .andExpect(jsonPath("$[2].eventKey").value("ENDING_B"))
                .andExpect(jsonPath("$[2].reachedPlaythroughs").value(4))
                .andExpect(jsonPath("$[2].reachRate").value(20.0))
                // 시각도 UTC 로 나간다 (D-009)
                .andExpect(jsonPath("$[0].firstOccurredAt").value("2026-08-02T01:30:00Z"));
    }

    // ── 선택 비율 ────────────────────────────────────────────────────

    @Test
    void 선택_비율은_에피소드마다_100퍼센트가_된다() throws Exception {
        mockMvc.perform(get("/stats/chapters/{chapterId}/choices", "qwer")
                        .header("X-Admin-Key", adminKey).param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                // EP01 — 50 / 30 / 20 (합 100)
                .andExpect(jsonPath("$[0].episodeId").value("EP01"))
                .andExpect(jsonPath("$[0].optionIndex").value(0))
                .andExpect(jsonPath("$[0].picks").value(50))
                .andExpect(jsonPath("$[0].pickRate").value(50.0))
                .andExpect(jsonPath("$[1].picks").value(30))
                .andExpect(jsonPath("$[1].pickRate").value(30.0))
                .andExpect(jsonPath("$[2].picks").value(20))
                .andExpect(jsonPath("$[2].pickRate").value(20.0))
                // 옵션이 하나뿐이면 100%
                .andExpect(jsonPath("$[3].episodeId").value("EP02_01"))
                .andExpect(jsonPath("$[3].pickRate").value(100.0))
                // EP03_02 — 60 / 40
                .andExpect(jsonPath("$[4].picks").value(36))
                .andExpect(jsonPath("$[4].pickRate").value(60.0))
                .andExpect(jsonPath("$[5].picks").value(24))
                .andExpect(jsonPath("$[5].pickRate").value(40.0));
    }

    @Test
    void 라벨이_원본_JSON에서_옵션_번호에_맞게_붙는다() throws Exception {
        // **이 테스트가 M5 에서 가장 값어치 있다.**
        // JSON_TABLE 의 FOR ORDINALITY 는 1부터 세고 우리 option_index 는 0부터다.
        // `- 1` 을 빠뜨리면 라벨만 한 칸 밀리고 **숫자는 전부 멀쩡하다** — 조용히 틀리는 종류다.
        // 그래서 라벨과 번호를 짝지어 단언한다. 숫자만 보는 테스트로는 절대 잡히지 않는다.
        mockMvc.perform(get("/stats/chapters/{chapterId}/choices", "qwer").header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].choiceLabel").value("성실하게 간다"))   // EP01 option 0
                .andExpect(jsonPath("$[1].choiceLabel").value("요령껏 간다"))     // EP01 option 1
                .andExpect(jsonPath("$[2].choiceLabel").value("그냥 간다"))       // EP01 option 2
                .andExpect(jsonPath("$[3].choiceLabel").value("계속 걷는다"))
                .andExpect(jsonPath("$[4].choiceLabel").value("왼쪽으로"))        // EP03_02 option 0
                .andExpect(jsonPath("$[5].choiceLabel").value("오른쪽으로"))      // EP03_02 option 1
                // 에피소드 제목은 색인(chapter_episodes)에서, 라벨은 원본 JSON 에서 온다
                .andExpect(jsonPath("$[0].episodeTitle").value("출발"))
                .andExpect(jsonPath("$[4].episodeTitle").value("갈림길"));
    }

    @Test
    void version을_생략하면_최신_버전이다() throws Exception {
        // seed 에는 v1 하나뿐이라 결과가 같아야 한다. 같다는 것이 곧 "최신을 골랐다" 의 증거다.
        mockMvc.perform(get("/stats/chapters/{chapterId}/choices", "qwer").header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].picks").value(50));
    }

    @Test
    void 없는_챕터와_없는_버전은_404다() throws Exception {
        // 빈 배열이 아니라 404 다. "그런 콘텐츠가 없다" 와 "선택이 0건이다" 는 다른 사실이고,
        // 빈 배열을 주면 클라는 "아직 아무도 안 골랐다" 로 읽는다 — 조용히 틀린 답이다 (M3 의 구분 그대로).
        mockMvc.perform(get("/stats/chapters/{chapterId}/choices", "nope").header("X-Admin-Key", adminKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/stats/chapters/{chapterId}/choices", "qwer")
                        .header("X-Admin-Key", adminKey).param("version", "99"))
                .andExpect(status().isNotFound());
    }

    // ── 사용자 요약 ──────────────────────────────────────────────────

    @Test
    void 사용자_요약이_팬아웃_없이_센다() throws Exception {
        // amiya = 회차 1~4. 슬롯은 회차당 1개(회차 11 미만이므로) → 4개.
        // playSeconds = 100+200+300+400 = 1000.
        // 여기서 슬롯을 4 가 아니라 다른 수로 세면 COUNT(DISTINCT) 가 빠진 것이다.
        mockMvc.perform(get("/users/{userId}/summary", 1).header("Authorization", amiyaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.username").value("amiya"))
                .andExpect(jsonPath("$.playthroughs").value(4))
                .andExpect(jsonPath("$.endedPlaythroughs").value(4))
                .andExpect(jsonPath("$.saveSlots").value(4))
                .andExpect(jsonPath("$.choices").value(40))
                .andExpect(jsonPath("$.playSeconds").value(1000))
                .andExpect(jsonPath("$.lastPlayedAt").value("2026-08-02T04:00:00Z"));
    }

    @Test
    void 진행_중인_회차만_가진_사용자는_종료가_0이다() throws Exception {
        // eyja = 회차 17~20. 전부 진행 중(ended_at NULL) → endedPlaythroughs 0.
        // 슬롯은 회차당 2개(11 이상) → 8개.
        // playSeconds = 슬롯1(1700+1800+1900+2000=7400) + 슬롯2(170+180+190+200=740) = 8140.
        //   ← 슬롯이 2개인 회차의 합을 빠뜨리면 7400 이 나온다. 팬아웃과 반대 방향의 실수다.
        mockMvc.perform(get("/users/{userId}/summary", 5).header("Authorization", eyjaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("eyja"))
                .andExpect(jsonPath("$.playthroughs").value(4))
                .andExpect(jsonPath("$.endedPlaythroughs").value(0))
                .andExpect(jsonPath("$.saveSlots").value(8))
                .andExpect(jsonPath("$.choices").value(40))
                .andExpect(jsonPath("$.playSeconds").value(8140));
    }

    @Test
    void 남의_summary는_403이다() throws Exception {
        // M6 이전에는 "없는 사용자 → 404" 였다. 이제 /users/{id}/** 는 본인만이라(인터셉터),
        // 남의 id 든 없는 id 든 내 것이 아닌 순간 403 이다 — 404 분기는 HTTP 로 닿지 않는다.
        mockMvc.perform(get("/users/{userId}/summary", 5).header("Authorization", amiyaBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ── M6: 보호 자체의 확인 ─────────────────────────────────────────

    @Test
    void 관리자_키_없이_stats는_401이다() throws Exception {
        // D-013. 집계는 관리자용이다 — 키가 없으면 로그인 토큰이 있어도 소용없다.
        mockMvc.perform(get("/stats/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/stats/events").header("Authorization", amiyaBearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰_없이_summary는_401이다() throws Exception {
        mockMvc.perform(get("/users/{userId}/summary", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
