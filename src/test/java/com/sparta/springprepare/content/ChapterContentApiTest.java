package com.sparta.springprepare.content;

import com.sparta.springprepare.support.DbCleaner;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 완료 기준을 코드로 옮긴 것 (PLAN M1 + docs/plans/M1.md §7).
 *
 * 샘플은 Unity 레포에서 가져온 실제 산출물 qwer.progression.json 이다 —
 * 노드 8개, 옵션 3/1/0/2/1/1/0/0, EventKey 는 전부 빈 문자열.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChapterContentApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private byte[] sample;

    @BeforeEach
    void setUp() throws IOException {
        new DbCleaner(jdbc).clean();
        sample = readResource("/content/qwer.progression.json");
    }

    // ── 수입과 멱등성 ────────────────────────────────────────────────

    @Test
    void 첫_수입은_201이고_색인이_노드_수만큼_생긴다() throws Exception {
        mockMvc.perform(post("/content/chapters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sample))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chapterId").value("qwer"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.episodeCount").value(8));

        assertThat(count("chapter_contents")).isEqualTo(1);
        assertThat(count("chapter_episodes")).isEqualTo(8);
    }

    @Test
    void 같은_파일을_다시_올리면_200이고_행이_늘지_않는다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        // 정상 경로는 checksum 조회이지 UNIQUE 위반이 아니다 — 409 가 아니라 200 이어야 한다.
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.episodeCount").value(8));

        assertThat(count("chapter_contents")).isEqualTo(1);
        assertThat(count("chapter_episodes")).isEqualTo(8);
    }

    @Test
    void 바이트가_한_글자라도_다르면_새_버전이다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        // 끝에 개행 하나만 더한다 — JSON 의 "의미"는 완전히 같지만 바이트가 다르다.
        // 서버는 이것을 다른 파일로 본다. 재수입 판정이 바이트 기준이라는 뜻이고, 버그가 아니라 정의다.
        byte[] oneMoreNewline = new byte[sample.length + 1];
        System.arraycopy(sample, 0, oneMoreNewline, 0, sample.length);
        oneMoreNewline[sample.length] = '\n';

        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(oneMoreNewline))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(count("chapter_contents")).isEqualTo(2);
        assertThat(count("chapter_episodes")).isEqualTo(16);
    }

    // ── 색인 정확성 ──────────────────────────────────────────────────

    @Test
    void 색인의_option_count와_event_key가_원본과_같다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        // EP01 은 선택지가 3개, EP04_01 은 0개 (원본 확인값)
        assertThat(optionCount("EP01")).isEqualTo(3);
        assertThat(optionCount("EP03_02")).isEqualTo(2);
        assertThat(optionCount("EP04_01")).isZero();

        // 이 샘플의 EventKey 는 전부 빈 문자열이다 → M3 의 이벤트 테스트에는 변형 파일이 필요하다.
        Integer withEventKey = jdbc.sql("SELECT COUNT(*) FROM chapter_episodes WHERE event_key <> ''")
                .query(Integer.class).single();
        assertThat(withEventKey).isZero();
    }

    // ── 원본 보존 (D-006: JSON 컬럼, 의미 비교) ──────────────────────

    @Test
    void 내려받은_본문은_바이트는_달라도_의미는_같다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/content/chapters/qwer/latest"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] downloaded = result.getResponse().getContentAsByteArray();

        // MySQL JSON 컬럼이 공백·들여쓰기를 제거하고 키 순서를 정규화한다 → 바이트는 다르다.
        assertThat(downloaded).isNotEqualTo(sample);
        assertThat(downloaded.length).isLessThan(sample.length);

        // 그러나 파싱한 트리는 완전히 같다. 이것이 D-006 이 말한 "diff 0 대신 의미 비교"다.
        JsonNode original = objectMapper.readTree(sample);
        JsonNode roundTripped = objectMapper.readTree(downloaded);
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void 한글_라벨이_깨지지_않는다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/content/chapters/qwer/1"))
                .andExpect(status().isOk())
                .andReturn();

        // StringHttpMessageConverter 의 기본 charset 은 ISO-8859-1 이다.
        // application/json 일 때만 UTF-8 로 쓰는 예외 규칙이 있어서 한글이 산다 — 그 규칙에 의존하고 있으므로 지켜본다.
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("선택지 골라.");
    }

    // ── 트랜잭션 롤백 ────────────────────────────────────────────────

    @Test
    void 색인_INSERT가_실패하면_본문도_남지_않는다() throws Exception {
        // 같은 EpisodeId 가 둘 → chapter_episodes 의 PK (chapter_content_id, episode_id) 가 막는다.
        // 일부러 예외를 던지는 테스트 전용 코드를 넣지 않고 실제 DB 제약으로 실패시킨다.
        byte[] duplicated = """
                {
                  "ChapterId": "dup",
                  "DisplayName": "중복 에피소드",
                  "StartEpisodeId": "EP01",
                  "Stats": [],
                  "Nodes": [
                    { "EpisodeId": "EP01", "Title": "", "EventKey": "", "NextOptions": [] },
                    { "EpisodeId": "EP01", "Title": "", "EventKey": "", "NextOptions": [] }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        // 상태 코드는 409(DuplicateKey) 또는 400(DataIntegrity) 중 하나다 —
        // 배치 INSERT 실패 시 드라이버가 BatchUpdateException 에 어떤 SQLState/errorCode 를 싣는지에 달렸다.
        // 이 테스트가 지키는 것은 상태 코드가 아니라 아래 두 줄이다.
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(duplicated))
                .andExpect(status().is4xxClientError());

        assertThat(count("chapter_contents")).isZero();
        assertThat(count("chapter_episodes")).isZero();
    }

    // ── 입력 검증 ────────────────────────────────────────────────────

    @Test
    void Nodes가_비면_400이다() throws Exception {
        byte[] empty = """
                { "ChapterId": "x", "DisplayName": "", "StartEpisodeId": "EP01", "Nodes": [] }
                """.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(count("chapter_contents")).isZero();
    }

    @Test
    void ChapterId가_없으면_400이다() throws Exception {
        byte[] noId = """
                { "DisplayName": "", "StartEpisodeId": "EP01",
                  "Nodes": [ { "EpisodeId": "EP01", "NextOptions": [] } ] }
                """.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(noId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ── 조회 ────────────────────────────────────────────────────────

    @Test
    void 목록은_챕터마다_최신_버전_한_줄이다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        byte[] v2 = new byte[sample.length + 1];
        System.arraycopy(sample, 0, v2, 0, sample.length);
        v2[sample.length] = '\n';
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/content/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chapterId").value("qwer"))
                .andExpect(jsonPath("$[0].latestVersion").value(2));
    }

    @Test
    void 버전_목록에_checksum과_수입_시각이_있다() throws Exception {
        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/content/chapters/qwer/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].checksum").isNotEmpty())
                .andExpect(jsonPath("$[0].importedAt").isNotEmpty());
    }

    @Test
    void 없는_챕터와_없는_버전은_404다() throws Exception {
        mockMvc.perform(get("/content/chapters/nope/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/content/chapters").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/content/chapters/qwer/99"))
                .andExpect(status().isNotFound());
    }

    // ── helper ──────────────────────────────────────────────────────

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private int optionCount(String episodeId) {
        return jdbc.sql("SELECT option_count FROM chapter_episodes WHERE episode_id = :id")
                .param("id", episodeId)
                .query(Integer.class)
                .single();
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("테스트 리소스가 없다: " + path);
            }
            return in.readAllBytes();
        }
    }
}
