package com.sparta.springprepare.user;

/**
 * POST /users 요청 본문.
 * */
public record UserCreateRequest(String username, String password) {
}