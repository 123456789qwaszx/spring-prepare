package com.sparta.springprepare.auth;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인·로그아웃·토큰 검증 (M6-4·M6-5).
 *
 * 다른 클래스들은 AuthSupport 로 로그인을 "지나가고", 이 클래스만 로그인 자체를 "본다".
 * 완료 기준의 "토큰 없이 → 401" 은 각 영역 테스트(UserApiTest 등)에도 있다 —
 * 여기는 토큰의 수명(발급 → 사용 → 로그아웃/만료 → 무효)을 한 줄로 따라간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

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
    void 로그인하면_토큰과_userId와_만료시각을_준다() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"amiya\",\"password\":\"test-only\"}"))
                .andExpect(status().isOk())
                // SecureRandom 32바이트의 hex = 64자 (sessions.token CHAR(64))
                .andExpect(jsonPath("$.token").value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.userId").value((int) userId))
                // 만료 시각도 UTC 로 나간다 (D-009) — 클라가 시간대를 짐작할 자리가 없다
                .andExpect(jsonPath("$.expiresAt").value(matchesPattern(".*(Z|[+-]\\d{2}:\\d{2})$")));

        // 토큰은 곧 DB 행이다 — 로그인 한 번에 sessions 한 행.
        Long sessions = jdbc.sql("SELECT COUNT(*) FROM sessions WHERE user_id = :id")
                .param("id", userId).query(Long.class).single();
        assertThat(sessions).isEqualTo(1L);
    }

    @Test
    void 틀린_비밀번호와_없는_아이디는_구분되지_않는_401이다() throws Exception {
        // 구분해 주면 로그인 API 가 "계정 존재 여부 조회기"가 된다 (AuthService 주석).
        // 상태 코드만이 아니라 **본문까지 같아야** 밖에서 구분할 수 없다.
        String wrongPassword = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"amiya\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(noSuchUser).isEqualTo(wrongPassword);
    }

    @Test
    void 로그아웃하면_그_토큰은_즉시_무효다() throws Exception {
        String bearer = AuthSupport.login(mockMvc, "amiya");

        // 살아 있는 동안은 통과
        mockMvc.perform(get("/users/{id}", userId).header("Authorization", bearer))
                .andExpect(status().isOk());

        // 로그아웃 = 행 삭제. 돌려줄 것이 없으니 204.
        mockMvc.perform(post("/auth/logout").header("Authorization", bearer))
                .andExpect(status().isNoContent());

        // 같은 토큰이 그 즉시 죽는다 — 세션이 DB 행이라서 가능한 일 (JWT 는 못 한다, AuthService 주석).
        mockMvc.perform(get("/users/{id}", userId).header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 로그아웃은_멱등하다() throws Exception {
        String bearer = AuthSupport.login(mockMvc, "amiya");

        mockMvc.perform(post("/auth/logout").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        // 두 번째도 204 — "그 토큰이 더는 유효하지 않다"는 목적은 이미 달성돼 있다.
        mockMvc.perform(post("/auth/logout").header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }

    @Test
    void 만료된_토큰은_401이다() throws Exception {
        // 만료를 기다릴 수는 없으니 과거로 만료된 행을 직접 심는다. 토큰 형식은 실제와 같은 64자 hex.
        String expired = "e".repeat(64);
        jdbc.sql("""
                        INSERT INTO sessions (token, user_id, expires_at)
                        VALUES (:token, :userId, '2020-01-01 00:00:00')
                        """)
                .param("token", expired)
                .param("userId", userId)
                .update();

        mockMvc.perform(get("/users/{id}", userId).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void Bearer_접두사가_없으면_401이다() throws Exception {
        String bearer = AuthSupport.login(mockMvc, "amiya");
        String bareToken = bearer.substring("Bearer ".length());

        // C3 의 함정을 테스트로 박제 — 토큰이 유효해도 형식이 틀리면 401 이다.
        mockMvc.perform(get("/users/{id}", userId).header("Authorization", bareToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인의_빈_입력은_400이다() throws Exception {
        // 401(인증 실패)이 아니라 400(형식 위반)이다 — 시도조차 아니다. D-005 의 수동 if 그대로.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
