package com.sparta.springprepare.common;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 에러 형식 통일 (M6-8). 모든 4xx/5xx 는 {@code code}·{@code message} 를 가진다 (D-004 M6 갱신).
 *
 * 각 영역 테스트가 자기 에러(404·409·400)의 code 를 이미 단언한다 — 이 클래스는
 * **핸들러 바깥에서 새던 것들**만 본다: 깨진 JSON(F20), 타입 불일치, 없는 경로, 틀린 메서드.
 * 전부 M5 까지는 Spring 기본 형식({timestamp,status,error,path})으로 나가던 응답이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorFormatTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        new DbCleaner(jdbc).clean();
    }

    @Test
    void 깨진_JSON은_MALFORMED_JSON_400이다() throws Exception {
        // F20 의 마감. M2 검증에서 이 요청이 GlobalExceptionHandler 를 우회해
        // Spring 기본 형식으로 나가는 것을 목격했다 — 이제 code 로 분기할 수 있다.
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"amiya\", "))   // 잘린 본문 — 평범한 클라 버그
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                // 파서 내부 문자열(위치·토큰)은 응답에 싣지 않는다 — 로그로만 간다
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void 경로_변수의_타입이_틀리면_TYPE_MISMATCH_400이다() throws Exception {
        // /users/abc — 인터셉터의 숫자 패턴(/users/\d+)에 안 걸려 본인 확인 없이 통과하고,
        // 컨트롤러 바인딩(@PathVariable long)이 던진다. 토큰은 필요하다 (보호 경로).
        Fixtures.insertUser(jdbc, "amiya");
        String bearer = AuthSupport.login(mockMvc, "amiya");

        mockMvc.perform(get("/users/abc").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
    }

    @Test
    void 없는_경로도_에러_형식을_지킨다_404() throws Exception {
        // NoResourceFoundException 은 자기 상태 코드(404)를 아는 예외다 (ErrorResponse 인터페이스).
        // 포괄 핸들러가 그 코드를 **보존**하고 본문만 우리 형식으로 바꾼다 —
        // 이걸 안 하면 "없는 URL 인데 500" 이 된다 (GlobalExceptionHandler 주석).
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 틀린_메서드도_에러_형식을_지킨다_405() throws Exception {
        // /users/{id} 에 DELETE 매핑은 없다. 이 예외는 핸들러 매핑 단계에서 나므로
        // 인터셉터(토큰)보다 먼저다 — 토큰 없이도 401 이 아니라 405 가 맞다.
        mockMvc.perform(delete("/users/{id}", 1))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
