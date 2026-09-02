package com.sparta.springprepare.bookmark;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 즐겨찾기 (M8-A, D-021·D-022).
 *
 * <p>세이브 테스트와 무엇이 다른가: revision 이 없고, 409 가 없고, 이력이 없다. 있는 것은 <b>멱등 upsert</b>
 * (같은 클라 id 로 PUT 하면 201 → 200), <b>soft delete</b>(지워도 행은 남고 다시 PUT 하면 되살아난다),
 * 그리고 출처 회차와의 <b>순서 독립 링크</b>(회차가 먼저든 즐겨찾기가 먼저든 결국 이어진다) 셋이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookmarkApiTest {

    private static final String SNAPSHOT = """
            {"sceneIndex":4,"variables":{"$int":7},"backlog":[{"speaker":"A","text":"…"}]}
            """;

    /** 출처 회차의 클라 id (Guid "N" 32 hex 자리). */
    private static final String PT = "a".repeat(32);

    /** 클라가 오프셋 붙여 보낸 "찍은 시각"과 같은 순간의 UTC — D-009 가 즐겨찾기에도 적용되는지 본다. */
    private static final String KST_TIME = "2026-09-02T21:00:00+09:00";
    private static final String SAME_INSTANT_UTC = "2026-09-02T12:00:00Z";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() throws Exception {
        new DbCleaner(jdbc).clean();
        userId = Fixtures.insertUser(jdbc, "amiya");
        Fixtures.insertChapter(jdbc, "qwer", 1);
        bearer = AuthSupport.login(mockMvc, "amiya");
    }

    // ── upsert ──────────────────────────────────────────────────────

    @Test
    void 처음_올리면_201이고_같은_id로_다시_올리면_200이다() throws Exception {
        mockMvc.perform(putBookmark("b1", body("첫 라벨", 4)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/users/" + userId + "/bookmarks/b1"))
                .andExpect(jsonPath("$.clientBookmarkId").value("b1"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // 같은 id, 다른 내용 — 갱신이지 새 행이 아니다. 마지막 쓰기가 이긴다(revision 없음).
        mockMvc.perform(putBookmark("b1", body("고친 라벨", 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientBookmarkId").value("b1"));

        assertThat(count()).isEqualTo(1);
        assertThat(label("b1")).isEqualTo("고친 라벨");
    }

    @Test
    void 목록은_메타만이고_스냅샷은_단건에서만_온다() throws Exception {
        // 나중 시각을 먼저 올린다 — 목록이 "올린 순서"가 아니라 "찍은 순서"(created_at)인지 보려고.
        mockMvc.perform(putBookmark("b2", bodyAt("둘째", 9, "2026-09-02T13:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(putBookmark("b1", bodyAt("첫째", 4, KST_TIME)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/users/{userId}/bookmarks", userId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].clientBookmarkId").value("b1"))
                .andExpect(jsonPath("$[0].label").value("첫째"))
                .andExpect(jsonPath("$[0].preview").value("미리보기"))
                .andExpect(jsonPath("$[0].chapterId").value("qwer"))
                .andExpect(jsonPath("$[0].chapterVersion").value(1))
                .andExpect(jsonPath("$[0].playthroughClientId").value(PT))
                .andExpect(jsonPath("$[0].sceneIndex").value(4))
                .andExpect(jsonPath("$[0].createdAt").value(SAME_INSTANT_UTC))   // +09:00 로 보냈고 Z 로 돌아온다
                .andExpect(jsonPath("$[0].snapshot").doesNotExist())            // 목록은 스냅샷을 싣지 않는다
                .andExpect(jsonPath("$[1].clientBookmarkId").value("b2"))
                .andExpect(jsonPath("$[1].sceneIndex").value(9));

        // 단건은 스냅샷을 그대로 돌려준다 — 서버는 열지 않는다.
        MvcResult result = mockMvc.perform(get("/users/{userId}/bookmarks/{id}", userId, "b1")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("첫째"))
                .andExpect(jsonPath("$.snapshot.sceneIndex").value(4))
                .andReturn();

        JsonNode sent = objectMapper.readTree(SNAPSHOT);
        JsonNode returned = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("snapshot");
        assertThat(returned).isEqualTo(sent);
    }

    // ── 출처 회차 링크 (D-020 의 즐겨찾기판) ───────────────────────

    @Test
    void 출처_회차는_찾히면_지금_잇고_아니면_회차가_올_때_잇는다() throws Exception {
        // 회차 PT 는 아직 서버에 없다 — 즐겨찾기가 먼저 왔다. 클라 id 만 들고 기다린다.
        mockMvc.perform(putBookmark("b1", body("먼저", 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playthroughId").doesNotExist());
        assertThat(playthroughIdOf("b1")).isNull();

        // 회차가 도착한다 → 그 순간 즐겨찾기 쪽이 되채워진다.
        long playthroughId = createPlaythrough(PT);
        assertThat(playthroughIdOf("b1")).isEqualTo(playthroughId);

        mockMvc.perform(get("/users/{userId}/bookmarks/{id}", userId, "b1").header("Authorization", bearer))
                .andExpect(jsonPath("$.playthroughId").value((int) playthroughId));

        // 회차가 있는 상태에서 올린 즐겨찾기는 응답에서부터 서버 id 를 안다.
        mockMvc.perform(putBookmark("b2", body("나중", 5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playthroughId").value((int) playthroughId));
    }

    // ── soft delete ─────────────────────────────────────────────────

    @Test
    void 삭제는_soft이고_멱등이며_같은_id로_다시_PUT하면_되살아난다() throws Exception {
        mockMvc.perform(putBookmark("b1", body("라벨", 4))).andExpect(status().isCreated());

        mockMvc.perform(deleteBookmark("b1")).andExpect(status().isNoContent());

        // 지운 것은 없는 것이다 — 단건 404, 목록 0. 그러나 행은 남아 있다.
        mockMvc.perform(get("/users/{userId}/bookmarks/{id}", userId, "b1").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/users/{userId}/bookmarks", userId).header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(count()).isEqualTo(1);
        assertThat(isDeleted("b1")).isTrue();

        // 또 지워도 204 — 없는 id 를 지워도 204. 클라의 재시도가 실패로 보이면 안 된다.
        mockMvc.perform(deleteBookmark("b1")).andExpect(status().isNoContent());
        mockMvc.perform(deleteBookmark("never")).andExpect(status().isNoContent());

        // 같은 id 로 다시 PUT → 200(갱신 경로) 이고 되살아난다. 행이 있었으므로 201 이 아니다.
        mockMvc.perform(putBookmark("b1", body("부활", 4)))
                .andExpect(status().isOk());
        assertThat(isDeleted("b1")).isFalse();
        mockMvc.perform(get("/users/{userId}/bookmarks/{id}", userId, "b1").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("부활"));
    }

    // ── 실패 경로 ───────────────────────────────────────────────────

    @Test
    void 없는_콘텐츠_버전은_404이고_아무것도_저장하지_않는다() throws Exception {
        mockMvc.perform(putBookmark("b1", body("라벨", 4).replace("\"chapterVersion\":1", "\"chapterVersion\":9")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(count()).isZero();
    }

    @Test
    void 필수값이_빠지면_400이다() throws Exception {
        // label 없음
        mockMvc.perform(putBookmark("b1", """
                {"chapterId":"qwer","chapterVersion":1,"sceneIndex":4,"snapshot":%s}
                """.formatted(SNAPSHOT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // snapshot 없음 — 즐겨찾기의 존재 이유가 스냅샷이다
        mockMvc.perform(putBookmark("b1", """
                {"label":"라벨","chapterId":"qwer","chapterVersion":1,"sceneIndex":4}
                """))
                .andExpect(status().isBadRequest());

        // 클라 id 가 32자를 넘는다 — 경로 변수도 검증 대상이다
        mockMvc.perform(putBookmark("x".repeat(33), body("라벨", 4)))
                .andExpect(status().isBadRequest());

        assertThat(count()).isZero();
    }

    @Test
    void 남의_즐겨찾기_경로는_403이다() throws Exception {
        // /users/{id}/… 라 인터셉터가 끊는다 — 서비스에 소유 검증이 없는 이유다.
        long bailuId = Fixtures.insertUser(jdbc, "bailu");

        mockMvc.perform(put("/users/{userId}/bookmarks/{id}", bailuId, "b1")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("라벨", 4).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/users/{userId}/bookmarks", bailuId).header("Authorization", bearer))
                .andExpect(status().isForbidden());

        assertThat(count()).isZero();
    }

    @Test
    void 스냅샷이_1MB를_넘으면_413이다() throws Exception {
        // 상한의 정확한 경계는 SaveSlotApiTest 가 잰다 — 여기서는 즐겨찾기도 같은 문에서 막히는지만 본다 (D-022).
        String tooBig = "{\"blob\":\"" + "a".repeat(1_048_566) + "\"}";   // 직렬화하면 1,048,577 바이트

        mockMvc.perform(putBookmark("b1", body("라벨", 4).replace(SNAPSHOT.strip(), tooBig)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(count()).isZero();
    }

    // ── helper ──────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder putBookmark(String clientId, String jsonBody) {
        return put("/users/{userId}/bookmarks/{id}", userId, clientId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder deleteBookmark(String clientId) {
        return delete("/users/{userId}/bookmarks/{id}", userId, clientId).header("Authorization", bearer);
    }

    private static String body(String label, int sceneIndex) {
        return bodyAt(label, sceneIndex, KST_TIME);
    }

    private static String bodyAt(String label, int sceneIndex, String createdAt) {
        return """
                {"label":"%s","preview":"미리보기","chapterId":"qwer","chapterVersion":1,
                 "playthroughClientId":"%s","sceneIndex":%d,"createdAt":"%s",
                 "snapshot":%s}
                """.formatted(label, PT, sceneIndex, createdAt, SNAPSHOT.strip());
    }

    private long createPlaythrough(String clientId) throws Exception {
        MvcResult result = mockMvc.perform(post("/users/{userId}/playthroughs", userId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientPlaythroughId\":\"" + clientId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("playthroughId").asLong();
    }

    private int count() {
        return jdbc.sql("SELECT COUNT(*) FROM bookmarks WHERE user_id = :uid")
                .param("uid", userId).query(Integer.class).single();
    }

    private String label(String clientId) {
        return jdbc.sql("SELECT label FROM bookmarks WHERE user_id = :uid AND client_id = :cid")
                .param("uid", userId).param("cid", clientId).query(String.class).single();
    }

    private Long playthroughIdOf(String clientId) {
        return jdbc.sql("SELECT playthrough_id FROM bookmarks WHERE user_id = :uid AND client_id = :cid")
                .param("uid", userId).param("cid", clientId).query(Long.class).optional().orElse(null);
    }

    private boolean isDeleted(String clientId) {
        Integer deleted = jdbc.sql("SELECT deleted_at IS NOT NULL FROM bookmarks WHERE user_id = :uid AND client_id = :cid")
                .param("uid", userId).param("cid", clientId).query(Integer.class).single();
        return deleted == 1;
    }
}
