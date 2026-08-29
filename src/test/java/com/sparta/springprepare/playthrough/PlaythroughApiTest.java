package com.sparta.springprepare.playthrough;

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

    @BeforeEach
    void setUp() {
        new DbCleaner(jdbc).clean();
        userId = Fixtures.insertUser(jdbc, "amiya");
    }

    @Test
    void 회차를_만들면_201이고_DB에_행이_생긴다() throws Exception {
        mockMvc.perform(post("/users/{userId}/playthroughs", userId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playthroughId").isNumber());

        Integer count = jdbc.sql("SELECT COUNT(*) FROM playthroughs WHERE user_id = :id")
                .param("id", userId).query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 없는_사용자의_회차_생성은_404다() throws Exception {
        // FK 위반(400)이 아니라 404 여야 한다 — 서비스가 먼저 조회하기 때문이다.
        mockMvc.perform(post("/users/{userId}/playthroughs", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 목록에_슬롯_수가_함께_나온다() throws Exception {
        long playthroughId = Fixtures.insertPlaythrough(jdbc, userId);
        long contentId = Fixtures.insertChapter(jdbc, "qwer", 1);
        insertSlot(playthroughId, 1, contentId);
        insertSlot(playthroughId, 2, contentId);

        mockMvc.perform(get("/users/{userId}/playthroughs", userId))
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
        mockMvc.perform(get("/users/{userId}/playthroughs", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 회차_종료는_멱등하다() throws Exception {
        long playthroughId = Fixtures.insertPlaythrough(jdbc, userId);

        MvcResult first = mockMvc.perform(post("/playthroughs/{id}/end", playthroughId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andReturn();

        // 두 번째 호출도 200 이고, 끝난 시각이 덮이지 않는다 (UPDATE ... WHERE ended_at IS NULL).
        MvcResult second = mockMvc.perform(post("/playthroughs/{id}/end", playthroughId))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void 없는_회차_종료는_404다() throws Exception {
        mockMvc.perform(post("/playthroughs/{id}/end", 999_999))
                .andExpect(status().isNotFound());
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
