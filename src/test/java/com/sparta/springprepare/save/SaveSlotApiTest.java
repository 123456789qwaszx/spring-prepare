package com.sparta.springprepare.save;

import com.sparta.springprepare.support.AuthSupport;
import com.sparta.springprepare.support.DbCleaner;
import com.sparta.springprepare.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세이브 업로드·복구 (PLAN M2 완료 기준 + docs/plans/M2.md §7).
 *
 * 스냅샷은 일부러 중첩된 모양으로 둔다 — 서버가 열지 않고 통째로 왕복시키는지 보려면
 * 평평한 객체보다 중첩된 쪽이 낫다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaveSlotApiTest {

    private static final String SNAPSHOT = """
            {
              "nodeName": "qwer_EP02_01",
              "lineId": "line:0007",
              "variables": { "$int": 5, "$power": 0, "$flag": true },
              "StageState": { "slots": ["c1", "c2"], "cast": { "c1": "amber" } },
              "ProgressionState": { "CurrentEpisodeId": "EP02_01", "Stats": { "int": 5, "power": 0 } }
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private long userId;
    private long playthroughId;
    private String bearer;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        userId = Fixtures.insertUser(jdbc, "amiya");
        playthroughId = Fixtures.insertPlaythrough(jdbc, userId);
        Fixtures.insertChapter(jdbc, "qwer", 1);
        Fixtures.insertChapter(jdbc, "qwer", 2);
        bearer = AuthSupport.login(mockMvc, "amiya");   // M6: 보호 경로
    }

    // ── revision ────────────────────────────────────────────────────

    @Test
    void 첫_업로드는_revision_1이고_다시_올리면_2가_된다() throws Exception {
        // ON DUPLICATE KEY UPDATE 절은 신규 INSERT 때 실행되지 않는다.
        // 그래서 INSERT 값에 1 을 직접 넣어야 첫 업로드가 0 이 아니라 1 이 된다.
        mockMvc.perform(putSlot(1, body("qwer", 1, "EP01", 10, "device-A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(putSlot(1, body(1, "qwer", 1, "EP02_01", 25, "device-A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        // 슬롯은 여전히 하나다 — upsert 이지 append 가 아니다.
        assertThat(count("save_slots")).isEqualTo(1);
    }

    // ── FK 를 404 로 번역 ───────────────────────────────────────────

    @Test
    void 없는_챕터_버전은_404다() throws Exception {
        // 서비스가 먼저 조회하지 않으면 FK 위반이 나고 400 CONSTRAINT_VIOLATION 이 된다.
        // 클라에게 맞는 답은 "그 콘텐츠 버전이 서버에 없다" 이므로 404 로 번역한다.
        mockMvc.perform(putSlot(1, body("qwer", 99, "EP01", 0, "device-A")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("save_slots")).isZero();
    }

    @Test
    void 없는_회차는_404다() throws Exception {
        mockMvc.perform(put("/playthroughs/{pid}/saves/{slotNo}", 999_999, 1)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("qwer", 1, "EP01", 0, "device-A")))
                .andExpect(status().isNotFound());
    }

    // ── 스냅샷 왕복 ─────────────────────────────────────────────────

    @Test
    void 스냅샷은_의미가_보존된_채_돌아온다() throws Exception {
        mockMvc.perform(putSlot(1, body("qwer", 1, "EP02_01", 25, "device-A")))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}", playthroughId, 1).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotNo").value(1))
                .andExpect(jsonPath("$.chapterId").value("qwer"))
                .andExpect(jsonPath("$.chapterVersion").value(1))
                .andExpect(jsonPath("$.currentEpisodeId").value("EP02_01"))
                .andExpect(jsonPath("$.playSeconds").value(25))
                .andExpect(jsonPath("$.device").value("device-A"))
                // @JsonRawValue 가 동작하면 snapshot 은 객체다. 안 되면 이스케이프된 문자열이 되어 이 단언이 깨진다.
                .andExpect(jsonPath("$.snapshot.nodeName").value("qwer_EP02_01"))
                .andReturn();

        JsonNode sent = objectMapper.readTree(SNAPSHOT);
        JsonNode returned = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .path("snapshot");

        // MySQL JSON 컬럼이 공백·키 순서를 정규화하므로 바이트는 다르다. 의미로 비교한다 (D-006 과 같은 이유).
        assertThat(returned).isEqualTo(sent);
    }

    @Test
    void 목록에는_스냅샷이_없다() throws Exception {
        mockMvc.perform(putSlot(1, body("qwer", 1, "EP01", 10, "device-A")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/playthroughs/{pid}/saves", playthroughId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slotNo").value(1))
                .andExpect(jsonPath("$[0].revision").value(1))
                // 목록 쿼리가 snapshot 컬럼을 아예 SELECT 하지 않는다
                .andExpect(jsonPath("$[0].snapshot").doesNotExist());
    }

    // ── 기기 ────────────────────────────────────────────────────────

    @Test
    void 기기_두_종류로_올리면_devices에_두_행이_생긴다() throws Exception {
        mockMvc.perform(putSlot(1, body("qwer", 1, "EP01", 10, "device-A")))
                .andExpect(status().isOk());
        mockMvc.perform(putSlot(2, body("qwer", 1, "EP01", 10, "device-B")))
                .andExpect(status().isOk());
        // 같은 기기로 또 올려도 늘지 않는다 — (user_id, device_key) UNIQUE 가 upsert 의 키다
        mockMvc.perform(putSlot(1, body(1, "qwer", 1, "EP01", 20, "device-A")))
                .andExpect(status().isOk());

        assertThat(count("devices")).isEqualTo(2);
    }

    @Test
    void 기기를_보내지_않으면_device는_null이다() throws Exception {
        String noDevice = """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP01",
                 "snapshot":%s,"playSeconds":0,"baseRevision":0}
                """.formatted(SNAPSHOT);

        mockMvc.perform(putSlot(1, noDevice)).andExpect(status().isOk());

        // save_slots.device_id 가 NULL 이어도 목록에서 슬롯이 사라지면 안 된다 (LEFT JOIN 인 이유).
        mockMvc.perform(get("/playthroughs/{pid}/saves", playthroughId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].device").doesNotExist());

        assertThat(count("devices")).isZero();
    }

    // ── 슬롯 번호 범위 (D-008) ──────────────────────────────────────

    @Test
    void 범위_밖_슬롯_번호는_400이다() throws Exception {
        // 0 과 128 은 TINYINT 범위 밖이거나 의미가 없다. DB 가 내는 "Out of range"(500 계열) 대신
        // 앱이 먼저 400 으로 거른다.
        mockMvc.perform(putSlot(0, body("qwer", 1, "EP01", 0, "device-A")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mockMvc.perform(putSlot(128, body("qwer", 1, "EP01", 0, "device-A")))
                .andExpect(status().isBadRequest());

        assertThat(count("save_slots")).isZero();
    }

    @Test
    void 슬롯_개수에는_상한이_없다() throws Exception {
        // D-008: 서버는 번호의 유효 범위만 보장하고 개수 상한은 클라이언트 정책이다.
        // 예전 계획의 "슬롯 4 → 400" 을 대체하는 기준이다 — 상한이 없음을 증명하는 쪽.
        for (int slotNo : new int[]{1, 5, 42, 127}) {
            mockMvc.perform(putSlot(slotNo, body("qwer", 1, "EP01", 0, "device-A")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/playthroughs/{pid}/saves", playthroughId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].slotNo").value(1))
                .andExpect(jsonPath("$[3].slotNo").value(127));
    }

    // ── 입력 검증·조회 ──────────────────────────────────────────────

    @Test
    void 필수값이_없으면_400이다() throws Exception {
        String noChapterId = """
                {"chapterVersion":1,"currentEpisodeId":"EP01","snapshot":{"a":1}}
                """;
        mockMvc.perform(putSlot(1, noChapterId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        String noSnapshot = """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP01","baseRevision":0}
                """;
        mockMvc.perform(putSlot(1, noSnapshot))
                .andExpect(status().isBadRequest());

        // M4: baseRevision 이 없으면 400. M2·M3 요청 형식과의 호환을 여기서 끊는다 — 의도한 것이다.
        // 선택으로 두면 "안 보낸 요청은 무조건 덮어쓴다"가 되어 낙관적 동시성이 있으나 마나 해진다.
        String noBase = """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP01","snapshot":%s}
                """.formatted(SNAPSHOT);
        mockMvc.perform(putSlot(1, noBase))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(count("save_slots")).isZero();
    }

    @Test
    void 없는_슬롯_조회는_404다() throws Exception {
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}", playthroughId, 3).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 챕터_버전을_올려_저장하면_그_버전을_가리킨다() throws Exception {
        mockMvc.perform(putSlot(1, body("qwer", 1, "EP01", 10, "device-A")))
                .andExpect(status().isOk());
        mockMvc.perform(putSlot(1, body(1, "qwer", 2, "EP01", 10, "device-A")))
                .andExpect(status().isOk());

        // 세이브는 chapter_id 가 아니라 특정 버전을 가리킨다 (schema.sql 주석).
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}", playthroughId, 1).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterVersion").value(2))
                .andExpect(jsonPath("$.revision").value(2));
    }

    // ── M8-A: 시간 둘·챕터 완료 (핸드오프 R4), 스냅샷 상한 (D-022) ─────

    @Test
    void 시간_둘과_챕터_완료는_왕복하고_생략하면_0과_false다() throws Exception {
        // 슬롯 1: F6 클라의 형식 — 셋을 다 보낸다. playSeconds 는 여전히 둘의 합이다.
        mockMvc.perform(putSlot(1, """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP02_01",
                 "snapshot":%s,"playSeconds":120,"deviceKey":"device-A","baseRevision":0,
                 "inheritedPlaySeconds":100,"ownPlaySeconds":20,"chapterCompleted":true}
                """.formatted(SNAPSHOT)))
                .andExpect(status().isOk());
        // 슬롯 2: F6 전 형식 — 셋이 없다. 컬럼 DEFAULT 가 아니라 요청 record 의 OrZero/OrFalse 가 채운다.
        mockMvc.perform(putSlot(2, body("qwer", 1, "EP01", 10, "device-A")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/playthroughs/{pid}/saves", playthroughId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inheritedPlaySeconds").value(100))
                .andExpect(jsonPath("$[0].ownPlaySeconds").value(20))
                .andExpect(jsonPath("$[0].playSeconds").value(120))
                .andExpect(jsonPath("$[0].chapterCompleted").value(true))
                .andExpect(jsonPath("$[1].inheritedPlaySeconds").value(0))
                .andExpect(jsonPath("$[1].ownPlaySeconds").value(0))
                .andExpect(jsonPath("$[1].chapterCompleted").value(false));

        // 단건(복구용)에도 같은 셋이 있다 — 목록과 단건이 같은 컬럼 목록을 쓴다는 확인.
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}", playthroughId, 1).header("Authorization", bearer))
                .andExpect(jsonPath("$.inheritedPlaySeconds").value(100))
                .andExpect(jsonPath("$.chapterCompleted").value(true))
                .andExpect(jsonPath("$.snapshot.nodeName").value("qwer_EP02_01"));
    }

    @Test
    void 스냅샷은_1_048_576_바이트까지_받고_하나_넘으면_413이다() throws Exception {
        // 서버가 재는 것은 요청 본문이 아니라 스냅샷을 다시 직렬화한 UTF-8 바이트다 (SnapshotLimit).
        // {"blob":"…"} 는 문자열 밖 11 바이트 — 그래서 a 를 1,048,565 개 넣으면 정확히 상한이다.
        String atLimit = "{\"blob\":\"" + "a".repeat(1_048_565) + "\"}";
        String overByOne = "{\"blob\":\"" + "a".repeat(1_048_566) + "\"}";

        mockMvc.perform(putSlot(1, bodyWithSnapshot(0, atLimit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(putSlot(1, bodyWithSnapshot(1, overByOne)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        // 413 은 아무것도 바꾸지 않는다 — revision 그대로.
        mockMvc.perform(get("/playthroughs/{pid}/saves/{slotNo}", playthroughId, 1).header("Authorization", bearer))
                .andExpect(jsonPath("$.revision").value(1));
    }

    // ── helper ──────────────────────────────────────────────────────

    private static String bodyWithSnapshot(long baseRevision, String snapshotJson) {
        return """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP01",
                 "snapshot":%s,"playSeconds":0,"baseRevision":%d}
                """.formatted(snapshotJson, baseRevision);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putSlot(
            int slotNo, String jsonBody) {
        return put("/playthroughs/{pid}/saves/{slotNo}", playthroughId, slotNo)
                .header("Authorization", bearer)     // M6: 모든 세이브 API 는 토큰 필수
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * baseRevision 을 생략한 형태 = **신규 슬롯**(0). M4 에서 baseRevision 이 필수가 됐지만,
     * 이 클래스의 테스트는 대부분 첫 업로드라 매번 0 을 적으면 눈에 띄어야 할 값이 묻힌다.
     * 두 번째 업로드처럼 base 가 중요한 자리에서만 아래 6-인자 형태를 쓴다.
     */
    private static String body(String chapterId, int version, String episodeId,
                               int playSeconds, String deviceKey) {
        return body(0, chapterId, version, episodeId, playSeconds, deviceKey);
    }

    private static String body(long baseRevision, String chapterId, int version, String episodeId,
                               int playSeconds, String deviceKey) {
        return """
                {"chapterId":"%s","chapterVersion":%d,"currentEpisodeId":"%s",
                 "snapshot":%s,"playSeconds":%d,"deviceKey":"%s","baseRevision":%d}
                """.formatted(chapterId, version, episodeId, SNAPSHOT, playSeconds, deviceKey, baseRevision);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
