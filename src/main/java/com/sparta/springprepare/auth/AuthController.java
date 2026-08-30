package com.sparta.springprepare.auth;

import com.sparta.springprepare.common.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /auth/login   {username, password} → 200 {token, userId, expiresAt} | 401
 * POST /auth/logout  Authorization: Bearer <token> → 204
 *
 * /auth/** 는 AuthInterceptor 경로 밖이다 — 로그인하려는 사람은 아직 토큰이 없다.
 * 그래서 logout 은 헤더를 여기서 직접 판다 (인터셉터가 넣어 주는 요청 속성이 없다).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** "Bearer " — 뒤의 공백까지가 접두사다 (M6 계획서 §6 C3). */
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * 204 No Content — 돌려줄 것이 없다. "성공했는데 본문이 없다"를 뜻하는 상태 코드가 이미 있으므로
     * 빈 JSON({})이나 메시지 객체를 지어내지 않는다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Authorization: Bearer <토큰> 헤더가 필요합니다.");
        }
        authService.logout(authorization.substring(BEARER_PREFIX.length()).trim());
        return ResponseEntity.noContent().build();
    }
}
