package com.sparta.springprepare.user;

import java.time.OffsetDateTime;

/**
 * users 테이블의 한 행. (PLAN §2.5: DB 행 매핑도 record)
 *
 * 필드명은 컬럼명의 camelCase 다. JdbcClient 의 .query(User.class) 가 생성자 파라미터명으로 컬럼을 찾고,
 * 없으면 snake_case 로 바꿔 다시 찾는다 (createdAt → created_at). 이 규칙 밖의 이름을 쓰면 값이 조용히 null 이 된다.
 *
 * password 를 여기 두는 이유: 이 record 는 "DB 행"이지 "응답"이 아니다. 응답은 UserResponse 가 따로 고른다.
 * M6 전까지 평문이다 (PLAN M0 "하지 않는 것").
 */
public record User(Long id, String username, String password, OffsetDateTime createdAt) {
}
