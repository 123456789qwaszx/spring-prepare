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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * definition 보관 (PLAN M1).
 *
 * 실물 game.definition.json 은 아직 Unity 레포에 없다. 그래도 구현·테스트가 가능한 이유는
 * 서버가 이 파일을 **전혀 해석하지 않기** 때문이다 (PLAN 1.4). 유효한 JSON 객체이기만 하면 된다.
 * 실물이 생기면 이 테스트의 샘플만 바꾸면 되고 서버 코드는 그대로다 — 그 사실 자체가 설계가 맞았다는 신호다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameDefinitionApiTest {

    private static final byte[] SAMPLE = """
            {
              "SchemaVersion": 1,
              "Stats": [ { "Key": "int", "DisplayName": "성실성" } ],
              "Unlocks": [ { "EventKey": "ENDING_A", "OpensChapter": "ch02" } ]
            }
            """.getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        new DbCleaner(jdbc).clean();
    }

    @Test
    void 첫_수입은_201_버전_1이다() throws Exception {
        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(SAMPLE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 같은_파일_재수입은_200이고_행이_늘지_않는다() throws Exception {
        // V2 마이그레이션으로 추가한 checksum 컬럼이 이 판정을 가능하게 한다 (D-007).
        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(SAMPLE))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(SAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 내용이_바뀌면_버전이_오른다() throws Exception {
        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(SAMPLE))
                .andExpect(status().isCreated());

        byte[] changed = """
                { "SchemaVersion": 2, "Stats": [], "Unlocks": [] }
                """.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void latest는_가장_높은_버전을_준다() throws Exception {
        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(SAMPLE))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/content/definition/latest"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray()))
                .isEqualTo(objectMapper.readTree(SAMPLE));
    }

    @Test
    void 없는_버전은_404고_수입_전_latest도_404다() throws Exception {
        mockMvc.perform(get("/content/definition/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/content/definition/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void JSON_객체가_아니면_400이다() throws Exception {
        byte[] array = "[1, 2, 3]".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/content/definition").contentType(MediaType.APPLICATION_JSON).content(array))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(count()).isZero();
    }

    private int count() {
        return jdbc.sql("SELECT COUNT(*) FROM game_definitions").query(Integer.class).single();
    }
}
