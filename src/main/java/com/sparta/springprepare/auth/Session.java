package com.sparta.springprepare.auth;

import java.time.OffsetDateTime;

/**
 * sessions 테이블의 한 행 (V5). 행이 있고 만료 전이면 유효한 로그인이다.
 *
 * expiresAt 은 D-009 규칙 그대로 — DB 의 DATETIME(UTC 벽시계)을 드라이버가
 * connectionTimeZone=UTC 로 읽어 OffsetDateTime 이 된다. 만료 판정도 UTC 끼리 비교한다.
 */
public record Session(String token, Long userId, OffsetDateTime expiresAt) {
}
