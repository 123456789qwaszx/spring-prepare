package com.sparta.springprepare.stats;

import com.sparta.springprepare.support.AuthSupport;
import com.sparta.springprepare.support.DbCleaner;
import com.sparta.springprepare.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챕터 개요 — 완주율·갈래·즐겨찾기 (M9-1, D-025).
 *
 * <p>seed 는 M8 의 열(chapter_completed·forked_from·bookmarks)을 전부 0 으로 두므로 여기서는 DbCleaner 위에
 * 손으로 놓는다: 회차 셋(뿌리 A·갈래 B — v1, 옛 회차 C — v2), 슬롯 넷(A 는 둘 — 팬아웃 재료), 즐겨찾기 셋(v1 산 것·v1 지운 것·v2).
 * 기대값은 코드를 돌리기 전에 적었다 (M5 규칙): v1 = 2 / 1 / 50.0 / 1 / 1, v2 = 1 / 0 / 0.0 / 0 / 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChapterOverviewApiTest {

    private static final String A = "a".repeat(32);
    private static final String B = "b".repeat(32);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Value("${app.admin-key}")
    String adminKey;

    private long userId;
    private String bearer;
    private long v1;
    private long v2;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        userId = Fixtures.insertUser(jdbc, "amiya");
        bearer = AuthSupport.login(mockMvc, "amiya");
        v1 = Fixtures.insertChapter(jdbc, "qwer", 1);
        v2 = Fixtures.insertChapter(jdbc, "qwer", 2);

        long a = create("{\"clientPlaythroughId\":\"" + A + "\"}");
        long b = create("{\"clientPlaythroughId\":\"" + B + "\",\"forkedFrom\":{\"clientPlaythroughId\":\"" + A + "\",\"sceneIndex\":2}}");
        long c = Fixtures.insertPlaythrough(jdbc, userId);   // 옛 회차 — client_id 없음

        insertSlot(a, 1, v1, true);
        insertSlot(a, 2, v1, true);     // 슬롯 둘 — 회차는 한 번만 세어져야 한다
        insertSlot(b, 1, v1, false);
        insertSlot(c, 1, v2, false);

        insertBookmark("bm-live", v1, false);
        insertBookmark("bm-gone", v1, true);
        insertBookmark("bm-v2", v2, false);
    }

    @Test
    void 완주율은_슬롯_1의_chapter_completed로_센다() throws Exception {
        mockMvc.perform(get("/stats/chapters/{chapterId}/overview", "qwer")
                        .header("X-Admin-Key", adminKey).param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.playthroughs").value(2))      // A·B. 슬롯 둘인 A 가 두 번 세어지면 3
                .andExpect(jsonPath("$.completed").value(1))         // A 만
                .andExpect(jsonPath("$.completionRate").value(50.0))
                .andExpect(jsonPath("$.forks").value(1))             // B
                .andExpect(jsonPath("$.bookmarks").value(1));        // 지운 것은 빠진다
    }

    @Test
    void 버전이_다르면_따로_센다_그리고_생략은_최신이다() throws Exception {
        // v2 에는 옛 회차 C 뿐 — 클라 id 가 없어도 회차는 회차다. 완주 0 은 NULL 이 아니라 0.0.
        mockMvc.perform(get("/stats/chapters/{chapterId}/overview", "qwer").header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.playthroughs").value(1))
                .andExpect(jsonPath("$.completed").value(0))
                .andExpect(jsonPath("$.completionRate").value(0.0))
                .andExpect(jsonPath("$.forks").value(0))
                .andExpect(jsonPath("$.bookmarks").value(1));
    }

    @Test
    void 사용자_요약도_끝낸_회차를_따로_센다() throws Exception {
        // D-025: "끝낸 회차"는 chapter_completed, "닫은 회차"(ended_at)는 클라가 부르지 않아 0 이다.
        mockMvc.perform(get("/users/{userId}/summary", userId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playthroughs").value(3))
                .andExpect(jsonPath("$.forks").value(1))
                .andExpect(jsonPath("$.completedPlaythroughs").value(1))
                .andExpect(jsonPath("$.endedPlaythroughs").value(0));
    }

    // ── helper ──────────────────────────────────────────────────────

    private long create(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/users/{userId}/playthroughs", userId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("playthroughId").asLong();
    }

    private void insertSlot(long playthroughId, int slotNo, long contentId, boolean completed) {
        jdbc.sql("""
                        INSERT INTO save_slots
                            (playthrough_id, slot_no, chapter_content_id, current_episode_id, snapshot, revision,
                             play_seconds, chapter_completed)
                        VALUES (:pid, :slotNo, :contentId, 'EP01', JSON_OBJECT('x', 1), 1, 10, :completed)
                        """)
                .param("pid", playthroughId)
                .param("slotNo", slotNo)
                .param("contentId", contentId)
                .param("completed", completed)
                .update();
    }

    private void insertBookmark(String clientId, long contentId, boolean deleted) {
        jdbc.sql("""
                        INSERT INTO bookmarks
                            (user_id, client_id, chapter_content_id, playthrough_client_id, scene_index,
                             label, preview, snapshot, created_at, deleted_at)
                        VALUES (:uid, :cid, :contentId, :pcid, 0,
                                'x', '', JSON_OBJECT('x', 1), CURRENT_TIMESTAMP,
                                CASE WHEN :deleted THEN CURRENT_TIMESTAMP ELSE NULL END)
                        """)
                .param("uid", userId)
                .param("cid", clientId)
                .param("contentId", contentId)
                .param("pcid", A)
                .param("deleted", deleted)
                .update();
    }
}
