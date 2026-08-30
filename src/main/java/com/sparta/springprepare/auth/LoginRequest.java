package com.sparta.springprepare.auth;

/** POST /auth/login 요청 본문. 회원가입(UserCreateRequest)과 같은 두 필드지만 용도가 달라 따로 둔다. */
public record LoginRequest(String username, String password) {
}
