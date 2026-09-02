package com.sparta.springprepare.playthrough;

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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회차 생성·목록·종료 (PLAN M2 → M8-A 에서 생성이 멱등이 됐다, D-019·D-020).
 *
 * <p>M8-A 이후 POST 는 본문이 필수다 — 클라가 매긴 회차 id({@code clientPlaythroughId})를 서버가 "있으면 그것,
 * 없으면 만든다"로 받는다. 본문 없는 옛 POST 는 400 이고 그것은 의도한 단절이다(F6 전 클라와 호환하지 않는다).
 *
 * <p>갈래(fork)는 부모를 <b>클라 id</b>로 잇는다. 부모의 서버 id 는 그 순간 찾히면 채우고, 아니면 부모가 도착할 때
 * 되채운다 — 그래서 "자식 먼저, 부모 나중" 이 별도의 테스트다. 도착 순서를 가정하지 않는 것이 D-020 의 핵심이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlaythroughApiTest {

    /** 클라 회차 id 는 Guid "N" 형식(32 hex)이다. 테스트에선 한 글자를 32번 — 눈으로 구분되면 된다. */
    private static final String A = "a".repeat(32);
    private static final String B = "b".repeat(32);
    private static final String C = "c".repeat(32);

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
        bearer = AuthSupport.login(mockMvc, "amiya");   // M6: 보호 경로라 매 테스트 토큰이 필요하다
    }

    // ── 생성 (멱등) ─────────────────────────────────────────────────

    @Test
    void 회차를_만들면_201이고_DB에_행이_생긴다() throws Exception {
        mockMvc.perform(create(newGame(A)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/playthroughs/")))
                .andExpect(jsonPath("$.playthroughId").isNumber())
                .andExpect(jsonPath("$.clientPlaythroughId").value(A));

        Integer count = jdbc.sql("SELECT COUNT(*) FROM playthroughs WHERE user_id = :id AND client_id = :cid")
                .param("id", userId).param("cid", A).query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 같은_클라_id로_다시_만들면_200이고_같은_회차다() throws Exception {
        // 재시도·재설치·다른 기기 — 어느 경우든 같은 id 는 같은 회차다. 두 번째는 아무것도 쓰지 않는다.
        long first = createAndReadId(newGame(A), 201);
        long second = createAndReadId(newGame(A), 200);

        assertThat(second).isEqualTo(first);
        assertThat(countPlaythroughs()).isEqualTo(1);
    }

    @Test
    void 형식이_틀리면_400이고_아무것도_만들지_않는다() throws Exception {
        // 1) 본문에 clientPlaythroughId 가 없다 — F6 전 클라의 POST 가 여기서 끊긴다 (D-019, 의도한 단절)
        mockMvc.perform(create("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 2) 갈래인데 부모의 클라 id 가 없다 — 서버 id 만으로는 잇지 않는다 (D-020)
        mockMvc.perform(create("""
                {"clientPlaythroughId":"%s","forkedFrom":{"playthroughId":1,"sceneIndex":3}}
                """.formatted(B)))
                .andExpect(status().isBadRequest());

        // 3) 장면 번호가 음수
        mockMvc.perform(create(fork(B, A, -1)))
                .andExpect(status().isBadRequest());

        assertThat(countPlaythroughs()).isZero();
    }

    @Test
    void 다른_사용자_밑으로는_회차를_만들_수_없다_403() throws Exception {
        // M6 이전에는 "없는 사용자 → 404"(서비스가 먼저 조회) 테스트였다. 이제 인터셉터가
        // 경로의 userId ≠ 토큰 userId 를 먼저 끊으므로 404 분기는 HTTP 로 닿을 수 없다 —
        // 서비스에는 방어로 남아 있다. 999999 의 실존 여부와 무관하게 403 이다.
        mockMvc.perform(post("/users/{userId}/playthroughs", 999_999)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newGame(A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 토큰_없이_회차_생성은_401이다() throws Exception {
        mockMvc.perform(post("/users/{userId}/playthroughs", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newGame(A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ── 갈래 (D-020) ────────────────────────────────────────────────

    @Test
    void 갈래는_부모를_클라_id로_잇고_서버_id는_찾히면_지금_채운다() throws Exception {
        long parent = createAndReadId(newGame(A), 201);
        long child = createAndReadId(fork(B, A, 3), 201);

        ForkRow row = forkRow(child);
        assertThat(row.forkedFromId()).isEqualTo(parent);
        assertThat(row.forkedFromClientId()).isEqualTo(A);
        assertThat(row.sceneIndex()).isEqualTo(3);

        // 목록에도 그대로 — 새 게임은 forkedFrom 자체가 없고, 갈래는 셋을 다 가진다.
        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].clientPlaythroughId").value(A))
                .andExpect(jsonPath("$[0].forkedFrom").doesNotExist())
                .andExpect(jsonPath("$[1].clientPlaythroughId").value(B))
                .andExpect(jsonPath("$[1].forkedFrom.playthroughId").value((int) parent))
                .andExpect(jsonPath("$[1].forkedFrom.clientPlaythroughId").value(A))
                .andExpect(jsonPath("$[1].forkedFrom.sceneIndex").value(3));
    }

    @Test
    void 자식이_먼저_오고_부모가_나중에_와도_링크가_닫힌다() throws Exception {
        // 큐가 회차별로 따로 비워지므로(핸드오프 §2) 갈래가 부모보다 먼저 서버에 닿을 수 있다.
        // 그때 갈래는 클라 id 만 들고 기다린다 — forked_from_id 는 NULL.
        long child = createAndReadId(fork(B, A, 3), 201);
        assertThat(forkRow(child).forkedFromId()).isNull();
        assertThat(forkRow(child).forkedFromClientId()).isEqualTo(A);

        mockMvc.perform(list())
                .andExpect(jsonPath("$[0].forkedFrom.clientPlaythroughId").value(A))
                .andExpect(jsonPath("$[0].forkedFrom.playthroughId").doesNotExist());   // 부모가 아직 서버에 없다

        // 부모가 도착하면 그 순간 자식 쪽이 되채워진다 — 자식은 다시 올라오지 않는다.
        long parent = createAndReadId(newGame(A), 201);
        assertThat(forkRow(child).forkedFromId()).isEqualTo(parent);

        mockMvc.perform(list())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].forkedFrom.playthroughId").value((int) parent));   // id 순이라 자식이 먼저다
    }

    @Test
    void 부모의_서버_id는_요청이_아니라_조회로_정한다() throws Exception {
        // 요청의 forkedFrom.playthroughId 는 소유 검증이 없는 숫자다 — 믿지 않고 클라 id 로 같은 사용자 안에서 찾는다.
        long parent = createAndReadId(newGame(A), 201);
        long child = createAndReadId("""
                {"clientPlaythroughId":"%s","forkedFrom":{"playthroughId":999999,"clientPlaythroughId":"%s","sceneIndex":0}}
                """.formatted(B, A), 201);

        assertThat(forkRow(child).forkedFromId()).isEqualTo(parent);
    }

    @Test
    void 요약은_갈래를_따로_센다() throws Exception {
        // playthroughs 는 갈래(줄) 단위, forks 는 그중 갈라진 것. 뿌리 수 = playthroughs - forks.
        // 뿌리 판정은 forked_from_client_id 로 한다 — 부모가 아직 안 온 갈래(C)도 갈래다.
        createAndReadId(newGame(A), 201);
        createAndReadId(fork(B, A, 1), 201);
        createAndReadId(fork(C, "d".repeat(32), 1), 201);   // 부모 미도착

        mockMvc.perform(get("/users/{userId}/summary", userId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playthroughs").value(3))
                .andExpect(jsonPath("$.forks").value(2));
    }

    // ── 목록 ────────────────────────────────────────────────────────

    @Test
    void 목록은_슬롯_1의_요약과_수_둘을_팬아웃_없이_준다() throws Exception {
        // 슬롯 둘 + 즐겨찾기 둘(+ 지운 것 하나)을 한 회차에 두고도 결과는 한 줄이어야 한다.
        // 셋을 그냥 조인하면 2×2 = 4줄이 나온다 (M5 user_summary 의 교훈).
        long playthroughId = createAndReadId(newGame(A), 201);
        long contentId = Fixtures.insertChapter(jdbc, "qwer", 1);
        insertSlot(playthroughId, 1, contentId, "EP02_01", 120, 100, 20, true);
        insertSlot(playthroughId, 2, contentId, "EP01", 5, 0, 5, false);
        insertBookmark("b1", contentId, A, false);
        insertBookmark("b2", contentId, A, false);
        insertBookmark("b3", contentId, A, true);    // 지운 것은 세지 않는다

        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) playthroughId))
                .andExpect(jsonPath("$[0].clientPlaythroughId").value(A))
                .andExpect(jsonPath("$[0].slotCount").value(2))
                .andExpect(jsonPath("$[0].bookmarkCount").value(2))
                // 슬롯 1 의 값 — 스냅샷을 열지 않고 이력 화면이 그리는 것 전부
                .andExpect(jsonPath("$[0].chapterId").value("qwer"))
                .andExpect(jsonPath("$[0].chapterVersion").value(1))
                .andExpect(jsonPath("$[0].currentEpisodeId").value("EP02_01"))
                .andExpect(jsonPath("$[0].chapterCompleted").value(true))
                .andExpect(jsonPath("$[0].inheritedPlaySeconds").value(100))
                .andExpect(jsonPath("$[0].ownPlaySeconds").value(20))
                .andExpect(jsonPath("$[0].playSeconds").value(120))
                .andExpect(jsonPath("$[0].lastSavedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].endedAt").doesNotExist());   // 진행 중이면 null → 필드가 빠진다
    }

    @Test
    void 슬롯이_없는_회차는_챕터_필드가_비고_수는_0이다() throws Exception {
        // LEFT JOIN 이라 회차는 남고 슬롯 쪽 값만 null 이다. 옛 회차(client_id 없음)도 목록에서 사라지지 않는다.
        Fixtures.insertPlaythrough(jdbc, userId);

        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].clientPlaythroughId").doesNotExist())
                .andExpect(jsonPath("$[0].slotCount").value(0))
                .andExpect(jsonPath("$[0].bookmarkCount").value(0))
                .andExpect(jsonPath("$[0].chapterId").doesNotExist())
                .andExpect(jsonPath("$[0].lastSavedAt").doesNotExist());
    }

    @Test
    void 회차가_없는_사용자는_빈_배열이다() throws Exception {
        // "사용자가 없다"(404)와 "회차가 0개다"(빈 배열)는 다르다.
        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── 종료 ────────────────────────────────────────────────────────

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

    // ── helper ──────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder create(String json) {
        return post("/users/{userId}/playthroughs", userId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder list() {
        return get("/users/{userId}/playthroughs", userId).header("Authorization", bearer);
    }

    private static String newGame(String clientId) {
        return "{\"clientPlaythroughId\":\"%s\"}".formatted(clientId);
    }

    private static String fork(String clientId, String parentClientId, int sceneIndex) {
        return """
                {"clientPlaythroughId":"%s","forkedFrom":{"clientPlaythroughId":"%s","sceneIndex":%d}}
                """.formatted(clientId, parentClientId, sceneIndex);
    }

    private long createAndReadId(String json, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(create(json))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("playthroughId").asLong();
    }

    private int countPlaythroughs() {
        return jdbc.sql("SELECT COUNT(*) FROM playthroughs WHERE user_id = :id")
                .param("id", userId).query(Integer.class).single();
    }

    /** 갈래 열 셋을 DB 에서 그대로 읽는다 — 응답이 아니라 저장된 것을 본다. */
    private record ForkRow(Long forkedFromId, String forkedFromClientId, Integer sceneIndex) {
    }

    private ForkRow forkRow(long playthroughId) {
        return jdbc.sql("""
                        SELECT forked_from_id, forked_from_client_id, forked_scene_index AS scene_index
                        FROM playthroughs WHERE id = :id
                        """)
                .param("id", playthroughId)
                .query(ForkRow.class)
                .single();
    }

    private void insertSlot(long playthroughId, int slotNo, long contentId, String episodeId,
                            int playSeconds, int inherited, int own, boolean completed) {
        jdbc.sql("""
                        INSERT INTO save_slots
                            (playthrough_id, slot_no, chapter_content_id, current_episode_id, snapshot, revision,
                             play_seconds, inherited_play_seconds, own_play_seconds, chapter_completed)
                        VALUES (:pid, :slotNo, :contentId, :episodeId, JSON_OBJECT('x', 1), 1,
                                :playSeconds, :inherited, :own, :completed)
                        """)
                .param("pid", playthroughId)
                .param("slotNo", slotNo)
                .param("contentId", contentId)
                .param("episodeId", episodeId)
                .param("playSeconds", playSeconds)
                .param("inherited", inherited)
                .param("own", own)
                .param("completed", completed)
                .update();
    }

    private void insertBookmark(String clientId, long contentId, String playthroughClientId, boolean deleted) {
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
                .param("pcid", playthroughClientId)
                .param("deleted", deleted)
                .update();
    }
}
