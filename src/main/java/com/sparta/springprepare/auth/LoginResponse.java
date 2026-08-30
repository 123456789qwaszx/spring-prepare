package com.sparta.springprepare.auth;

import java.time.OffsetDateTime;

/**
 * POST /auth/login 응답.
 *
 * userId 를 함께 주는 이유: 이후 모든 경로(/users/{id}/…)에 자기 id 가 필요한데,
 * 토큰만 주면 클라가 그것을 알아낼 방법이 없다. expiresAt 은 UTC 로 나간다 (D-009) —
 * 클라(M7)가 "곧 만료되니 미리 재로그인"을 판단할 재료다.
 *
 * 이 형식이 M7 이 쓸 로그인 계약이다 (M6 계획서 §9 "넘기는 것").
 */
public record LoginResponse(String token, Long userId, OffsetDateTime expiresAt) {
}
