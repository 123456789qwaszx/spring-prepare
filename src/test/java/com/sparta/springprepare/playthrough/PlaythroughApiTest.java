package com.sparta.springprepare.playthrough;

import com.sparta.springprepare.support.AuthSupport;
import com.sparta.springprepare.support.DbCleaner;
import com.sparta.springprepare.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회차 생성·목록·종료 (PLAN M2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlaythroughApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        userId = Fixtures.insertUser(jdbc, "amiya");
        bearer = AuthSupport.login(mockMvc, "amiya");   // M6: 보호 경로라 매 테스트 토큰이 필요하다
    }

    @Test
    void 회차를_만들면_201이고_DB에_행이_생긴다() throws Exception {
        mockMvc.perform(post("/users/{userId}/playthroughs", userId).header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playthroughId").isNumber());

        Integer count = jdbc.sql("SELECT COUNT(*) FROM playthroughs WHERE user_id = :id")
                .param("id", userId).query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 다른_사용자_밑으로는_회차를_만들_수_없다_403() throws Exception {
        // M6 이전에는 "없는 사용자 → 404"(서비스가 먼저 조회) 테스트였다. 이제 인터셉터가
        // 경로의 userId ≠ 토큰 userId 를 먼저 끊으므로 404 분기는 HTTP 로 닿을 수 없다 —
        // 서비스에는 방어로 남아 있다. 999999 의 실존 여부와 무관하게 403 이다.
        mockMvc.perform(post("/users/{userId}/playthroughs", 999_999).header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 토큰_없이_회차_생성은_401이다() throws Exception {
        mockMvc.perform(post("/users/{userId}/playthroughs", userId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 목록에_슬롯_수가_함께_나온다() throws Exception {
        long playthroughId = Fixtures.insertPlaythrough(jdbc, userId);
        long contentId = Fixtures.insertChapter(jdbc, "qwer", 1);
        insertSlot(playthroughId, 1, contentId);
        insertSlot(playthroughId, 2, contentId);

        mockMvc.perform(get("/users/{userId}/playthroughs", userId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) playthroughId))
                .andExpect(jsonPath("$[0].slotCount").value(2))
                .andExpect(jsonPath("$[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].endedAt").doesNotExist());   // 진행 중이면 null → 필드가 빠진다
    }

    @Test
    void 회차가_없는_사용자는_빈_배열이다() throws Exception {
        // "사용자가 없다"(404)와 "회차가 0개다"(빈 배열)는 다르다.
        mockMvc.perform(get("/users/{userId}/playthroughs", userId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 회차_종료는_멱등하다() throws Exception {
        long playthroughId = Fixtures.insertPlaythrough(jdbc, userId);

        MvcResult first = mockMvc.perform(post("/playthroughs/{id}/end", playthroughId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andReturn();

        // 두 번째 호출도 200 이고, 끝난 시각이 덮이지 않는다 (UPDATE ... WHERE ended_at IS NULL).
        MvcResult second = mockMvc.perform(post("/playthroughs/{id}/end", playthroughId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void 없는_회차_종료는_404다() throws Exception {
        // /playthroughs/** 는 경로에 소유자가 없어 인터셉터가 못 끊는다 — 서비스가 조회로 판정한다.
        // 없는 회차는 소유를 따질 대상조차 없으므로 403 이 아니라 404 다 (존재 확인이 먼저다, M6-6).
        mockMvc.perform(post("/playthroughs/{id}/end", 999_999).header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void 남의_회차_종료는_403이다() throws Exception {
        // M6 완료 기준 "남의 회차 → 403" 의 실물. bailu 의 회차를 amiya 토큰으로 끝내려 한다.
        long bailuId = Fixtures.insertUser(jdbc, "bailu");
        long bailuPlaythrough = Fixtures.insertPlaythrough(jdbc, bailuId);

        mockMvc.perform(post("/playthroughs/{id}/end", bailuPlaythrough).header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 끝나지 않았다 — 403 은 아무것도 바꾸지 않는다 (F29/F32 와 같은 결).
        Integer ended = jdbc.sql("SELECT ended_at IS NOT NULL FROM playthroughs WHERE id = :id")
                .param("id", bailuPlaythrough).query(Integer.class).single();
        assertThat(ended).isEqualTo(0);
    }

    private void insertSlot(long playthroughId, int slotNo, long contentId) {
        jdbc.sql("""
                        INSERT INTO save_slots
                            (playthrough_id, slot_no, chapter_content_id, current_episode_id, snapshot, revision)
                        VALUES (:pid, :slotNo, :contentId, 'EP01', JSON_OBJECT('x', 1), 1)
                        """)
                .param("pid", playthroughId)
                .param("slotNo", slotNo)
                .param("contentId", contentId)
                .update();
    }
}
