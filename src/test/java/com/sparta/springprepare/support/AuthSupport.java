package com.sparta.springprepare.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 테스트용 로그인 (M6 계획서 §6 C6). M6 부터 /users·/playthroughs 가 전부 토큰을 요구하므로,
 * 각 테스트가 setUp 에서 한 번 로그인해 {@code Authorization} 헤더 값을 들고 다닌다.
 *
 * 진짜 /auth/login 을 호출한다 — 세션 행을 SQL 로 심으면 빠르겠지만, 그러면 로그인 경로가
 * 깨져도 아무 테스트도 모른다. 로그인 API 자체의 검증은 AuthApiTest 가 한다.
 *
 * 토큰을 Jackson 이 아니라 정규식으로 꺼내는 이유: 지원 클래스가 매퍼 빈·설정에 묶이지 않게.
 * 토큰은 64자 hex 로 형태가 고정돼 있어 정규식이 오히려 정확하다.
 */
public final class AuthSupport {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([0-9a-f]{64})\"");

    private AuthSupport() {
    }

    /** Fixtures.insertUser 로 만든 사용자용 — 비밀번호가 TEST_PASSWORD 로 고정돼 있다. */
    public static String login(MockMvc mockMvc, String username) throws Exception {
        return login(mockMvc, username, Fixtures.TEST_PASSWORD);
    }

    /** seed 사용자('seed-only') 등 비밀번호가 다른 경우. @return "Bearer …" — 헤더에 그대로 넣는 값. */
    public static String login(MockMvc mockMvc, String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = TOKEN.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("로그인 응답에서 토큰을 찾지 못했다: " + body);
        }
        return "Bearer " + matcher.group(1);
    }
}
