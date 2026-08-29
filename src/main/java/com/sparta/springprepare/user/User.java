package com.sparta.springprepare.user;

import java.time.LocalDateTime;

/**
 * users 테이블의 한 행.
 */
public record User(Long id, String username, String password, LocalDateTime createdAt) {
}