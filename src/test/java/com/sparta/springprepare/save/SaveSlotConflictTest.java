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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멱등성과 충돌 (PLAN M4 + D-010).
 *
 * <p>{@code SaveSlotConcurrencyTest} 는 <b>진짜 동시</b>를 다루고, 이쪽은 <b>순차로 재현되는 시나리오</b>를 다룬다.
 * 동시 실행은 어느 쪽이 이길지 모르므로 "정확히 하나"까지만 단언할 수 있다. 여기서는 순서를 정해 놓고
 * 각 갈래가 정확히 무슨 응답을 주는지 본다 — 동시성 테스트가 못 하는 일이다.
 *
 * <h3>두 개의 409를 구별한다</h3>
 * <pre>
 *   DUPLICATE — "이미 있는 값이다".     DB 의 UNIQUE 가 막았다
 *   CONFLICT  — "네가 알던 상태가 낡았다". 조건부 UPDATE 가 0행이었다
 * </pre>
 * 클라의 대응이 다르다. 앞은 요청을 고쳐야 하고, 뒤는 <b>사용자에게 물어야 한다</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaveSlotConflictTest {

    private static final String SNAPSHOT = """
            {"nodeName":"qwer_EP01","variables":{"$int":1}}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private long playthroughId;
    private String bearer;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        long userId = Fixtures.insertUser(jdbc, "amiya");
        playthroughId = Fixtures.insertPlaythrough(jdbc, userId);
        long contentId = Fixtures.insertChapter(jdbc, "qwer", 1);
        Fixtures.insertEpisode(jdbc, contentId, "EP01", "");
        Fixtures.insertEpisode(jdbc, contentId, "EP02_01", "");
        Fixtures.insertEpisode(jdbc, contentId, "EP03_01", "MILESTONE_MIDPOINT");
        bearer = AuthSupport.login(mockMvc, "amiya");   // M6: 보호 경로
    }

    // ── 재전송 (D-010) ──────────────────────────────────────────────

    @Test
    void 같은_요청을_다시_보내면_replayed고_아무것도_쓰지_않는다() throws Exception {
        String first = body(0, 100, "device-A", choice(1, "EP01"));
        mockMvc.perform(putSlot(1, first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 응답을 못 받은 클라가 같은 요청을 그대로 다시 보낸다.
        mockMvc.perform(putSlot(1, first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.revision").value(1))       // 오르지 않았다
                .andExpect(jsonPath("$.acceptedChoices").value(0));

        assertThat(count("choice_history")).isEqualTo(1);
        assertThat(revisionOf(1)).isEqualTo(1L);
    }

    @Test
    void seq가_하나라도_새것이면_재전송이_아니다() throws Exception {
        mockMvc.perform(putSlot(1, body(0, 100, "device-A", choice(1, "EP01"))))
                .andExpect(status().isOk());

        // seq 1 은 이미 있지만 seq 2 는 새것이다 → 재전송이 아니라 새 요청이다.
        // 그런데 base 는 0 이고 서버는 1 이므로 조건부 UPDATE 가 0행 → 충돌.
        // (클라가 응답을 받았다면 base 1 로 보냈을 것이다. base 0 인 것 자체가 "낡았다"는 뜻이다.)
        mockMvc.perform(putSlot(1, body(0, 200, "device-A", choice(1, "EP01") + "," + choice(2, "EP02_01"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(count("choice_history")).isEqualTo(1);
        assertThat(revisionOf(1)).isEqualTo(1L);
    }

    // ── 충돌 (D-010) ────────────────────────────────────────────────

    @Test
    void 낡은_baseRevision은_409고_현재_서버_상태를_알려준다() throws Exception {
        // A 가 두 번 저장해 revision 을 2 로 올린다.
        mockMvc.perform(putSlot(1, body(0, 100, "device-A", choice(1, "EP01")))).andExpect(status().isOk());
        mockMvc.perform(putSlot(1, body(1, 200, "device-A", choice(2, "EP02_01")))).andExpect(status().isOk());

        // B 는 revision 1 일 때의 상태를 들고 있다 — 그 사이 A 가 한 번 더 썼다.
        mockMvc.perform(putSlot(1, body(1, 999, "device-B", choice(3, "EP03_01"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                // 다시 GET 하지 않아도 "무엇과 부딪혔는지"를 안다. M8 충돌 UI 가 보여줄 필드들이다.
                .andExpect(jsonPath("$.current.revision").value(2))
                .andExpect(jsonPath("$.current.device").value("device-A"))
                .andExpect(jsonPath("$.current.playSeconds").value(200))
                .andExpect(jsonPath("$.current.currentEpisodeId").value("EP02_01"))
                .andExpect(jsonPath("$.current.updatedAt").isNotEmpty());

        // B 의 것은 하나도 들어가지 않았다 — 슬롯도, 이력도.
        assertThat(revisionOf(1)).isEqualTo(2L);
        assertThat(count("choice_history")).isEqualTo(2);
    }

    @Test
    void choices가_없으면_판정하지_않고_409다() throws Exception {
        // D-010 의 핵심. PLAN 원문은 여기서 200 을 주라고 했지만 뒤집었다.
        // choices 가 없으면 재전송인지 충돌인지 알 수 없는데, 200 을 주면 **충돌을 재전송으로 오인**한다.
        // 그러면 다른 기기가 덮었는데도 클라는 "저장됐다"고 믿고 사용자에게 알리지 않는다.
        mockMvc.perform(putSlot(1, body(0, 100, "device-A"))).andExpect(status().isOk());

        // 세이브만 올리는 요청(이력 없음)은 드문 예외가 아니라 정상 경로다 — playSeconds 자동 저장 등.
        mockMvc.perform(putSlot(1, body(0, 150, "device-A")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.current.revision").value(1));

        // 올바른 base 로 보내면 물론 통과한다.
        mockMvc.perform(putSlot(1, body(1, 150, "device-A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    // ── force ───────────────────────────────────────────────────────

    @Test
    void force는_스냅샷을_덮고_이력은_새_것만_더한다() throws Exception {
        mockMvc.perform(putSlot(1, body(0, 100, "device-A", choice(1, "EP01")))).andExpect(status().isOk());
        mockMvc.perform(putSlot(1, body(1, 200, "device-A", choice(2, "EP02_01")))).andExpect(status().isOk());

        // B 는 409 를 받고 사용자에게 물은 뒤, **409 가 알려준 revision 2** 를 base 로 다시 보낸다.
        // seq 1·2 는 이미 있고 seq 3 만 새것이다.
        mockMvc.perform(putSlotForce(1, body(2, 500, "device-B",
                        choice(1, "EP01") + "," + choice(2, "EP02_01") + "," + choice(3, "EP03_01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                // 보낸 것은 3건, 기록한 것은 1건 — 여기서 acceptedChoices 가 처음으로 실제 정보를 담는다.
                .andExpect(jsonPath("$.acceptedChoices").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 스냅샷·기기는 B 의 것으로 덮였고, 이력은 A 것이 남은 채 B 것이 더해졌다.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/playthroughs/{pid}/saves/{slotNo}", playthroughId, 1)
                        .header("Authorization", bearer))
                .andExpect(jsonPath("$.playSeconds").value(500))
                .andExpect(jsonPath("$.device").value("device-B"));
        assertThat(count("choice_history")).isEqualTo(3);
    }

    @Test
    void force여도_낡은_base면_409다() throws Exception {
        // D-010: force 는 revision 조건을 **건너뛰지 않는다.**
        // 건너뛰면 409 를 받은 뒤 force 를 보내기까지 사이에 끼어든 세 번째 기기를 놓친다.
        // force 는 "무조건 덮어쓰기"가 아니라 "내가 본 그 상태 위에 덮어쓰기"다.
        mockMvc.perform(putSlot(1, body(0, 100, "device-A", choice(1, "EP01")))).andExpect(status().isOk());
        mockMvc.perform(putSlot(1, body(1, 200, "device-A", choice(2, "EP02_01")))).andExpect(status().isOk());

        mockMvc.perform(putSlotForce(1, body(1, 500, "device-B", choice(3, "EP03_01"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.current.revision").value(2));

        assertThat(revisionOf(1)).isEqualTo(2L);
    }

    // ── baseRevision 자체의 검증 ────────────────────────────────────

    @Test
    void 신규_슬롯은_baseRevision이_0이어야_한다() throws Exception {
        // 0 이 아니면 "있지도 않은 상태를 알고 있다"는 모순이다. FK 나 UNIQUE 가 아니라 앱이 막는다.
        mockMvc.perform(putSlot(5, body(1, 10, "device-A")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(count("save_slots")).isZero();
    }

    @Test
    void baseRevision이_없으면_400이다() throws Exception {
        String noBase = """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP01",
                 "snapshot":%s,"playSeconds":10,"deviceKey":"device-A"}
                """.formatted(SNAPSHOT);

        mockMvc.perform(putSlot(1, noBase))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(count("save_slots")).isZero();
    }

    // ── helper ──────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder putSlot(int slotNo, String jsonBody) {
        return put("/playthroughs/{pid}/saves/{slotNo}", playthroughId, slotNo)
                .header("Authorization", bearer)     // M6: 토큰 필수
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder putSlotForce(int slotNo, String jsonBody) {
        return putSlot(slotNo, jsonBody).param("force", "true");
    }

    private static String choice(int seq, String episodeId) {
        return """
                {"seq":%d,"episodeId":"%s","optionIndex":0,"chosenAt":"2026-08-29T11:%02d:07Z"}
                """.formatted(seq, episodeId, seq).strip();
    }

    /** choices 를 하나도 안 주면 "세이브만 올리는" 요청이 된다 — 정상 경로다. */
    private static String body(long baseRevision, int playSeconds, String deviceKey, String... choices) {
        String history = choices.length == 0 ? "" : ",\n \"choices\":[" + String.join(",", choices) + "]";
        return """
                {"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP02_01",
                 "snapshot":%s,"playSeconds":%d,"deviceKey":"%s","baseRevision":%d%s}
                """.formatted(SNAPSHOT, playSeconds, deviceKey, baseRevision, history);
    }

    private long revisionOf(int slotNo) {
        return jdbc.sql("SELECT revision FROM save_slots WHERE playthrough_id = :pid AND slot_no = :slotNo")
                .param("pid", playthroughId)
                .param("slotNo", slotNo)
                .query(Long.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
