package com.sparta.springprepare.user;

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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M0 완료 기준을 코드로 옮긴 것 (PLAN M0 "완료 기준" + docs/plans/M0.md §7).
 *
 * - @SpringBootTest: 진짜 컨텍스트, 진짜 DB (game_test, D-002). H2 로 바꾸지 않는다 — MySQL 의 UNIQUE·길이 제약을 보는 것이 목적.
 * - @AutoConfigureMockMvc: 서블릿 컨테이너 없이 DispatcherServlet 까지만 태운다. 필터·컨트롤러·예외 번역이 전부 실행된다.
 *   (Boot 4.x 패키지: org.springframework.boot.webmvc.test.autoconfigure — 3.x 와 다르다.)
 * - @ActiveProfiles("test"): application.properties 의 spring.profiles.active=local 을 덮어쓴다.
 * - @Transactional 로 롤백하지 않고 @BeforeEach 에서 지운다. 테스트 안에서 커밋된 행을 다른 커넥션이 보는지까지 확인하기 위해서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        new DbCleaner(jdbc).clean();
    }

    @Test
    void 사용자_생성은_201과_Location을_돌려주고_DB에_행이_생긴다() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "amiya", "password": "test-only"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/users/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("amiya"))
                .andExpect(jsonPath("$.password").doesNotExist());

        // DB 에 정말 들어갔는지 — 앱을 거치지 않고 직접 SELECT (Workbench 에서 보는 것과 같은 확인)
        Long count = jdbc.sql("SELECT COUNT(*) FROM users WHERE username = :username")
                .param("username", "amiya")
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);

        // created_at 은 INSERT 문에 없었으므로 DB DEFAULT 가 채운 값이다
        LocalDateTime createdAt = jdbc.sql("SELECT created_at FROM users WHERE username = :username")
                .param("username", "amiya")
                .query(LocalDateTime.class)
                .single();
        assertThat(createdAt).isNotNull();

        // M6-3: 저장된 것은 평문이 아니라 BCrypt 해시다 — 완료 기준 "SELECT password 에 평문 없음"의 자동판.
        String stored = jdbc.sql("SELECT password FROM users WHERE username = :username")
                .param("username", "amiya")
                .query(String.class)
                .single();
        assertThat(stored).startsWith("$2a$").isNotEqualTo("test-only");
    }

    @Test
    void 같은_username을_두_번_만들면_두_번째는_409다() throws Exception {
        String body = """
                {"username": "amiya", "password": "test-only"}
                """;

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // 앱은 "이미 있는지" SELECT 하지 않았다. DB 의 UNIQUE 가 막고, GlobalExceptionHandler 가 409 로 번역했다.
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        Long count = jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void 토큰_없이_사용자_조회는_401이다() throws Exception {
        // M6: GET /users/{id} 는 보호 경로다. 예전의 "없는 id → 404" 는 이제 HTTP 로 닿을 수 없다 —
        // 토큰이 없으면 401, 토큰이 있어도 남의 id 는 403 이 먼저다 (아래 테스트).
        // 서비스의 404 분기는 남아 있지만(방어), 정상 클라가 만날 일이 없는 코드가 됐다.
        mockMvc.perform(get("/users/{id}", 999_999))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 남의_id를_조회하면_403이다() throws Exception {
        Fixtures.insertUser(jdbc, "amiya");
        String bearer = AuthSupport.login(mockMvc, "amiya");

        // 999999 가 실존하는지는 중요하지 않다 — 내 id 가 아니라는 사실만으로 인터셉터가 끊는다.
        mockMvc.perform(get("/users/{id}", 999_999).header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 만든_사용자를_id로_다시_읽을_수_있다() throws Exception {
        MvcResult created = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "reader", "password": "test-only"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        // Location 헤더를 그대로 따라간다 — 201 응답의 Location 이 실제로 쓸 수 있는 주소인지 확인.
        // M6 부터는 본인 토큰이 있어야 열린다 — 가입에 쓴 비밀번호로 로그인부터 한다.
        String location = created.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String bearer = AuthSupport.login(mockMvc, "reader");

        mockMvc.perform(get(location).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reader"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void username이_비어_있으면_앱이_먼저_400으로_거른다() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "   ", "password": "test-only"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void username이_30자를_넘으면_DB가_막고_400이_된다() throws Exception {
        // 앱은 길이를 검사하지 않는다 (D-005). users.username VARCHAR(30) 이 막는다.
        // MySQL 8 기본 sql_mode(STRICT_TRANS_TABLES) 전제 — strict 가 아니면 잘려서 저장되고 이 테스트는 실패한다.
        String tooLong = "a".repeat(31);
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + tooLong + "\", \"password\": \"test-only\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));

        Long count = jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        assertThat(count).isEqualTo(0L);
    }
}
