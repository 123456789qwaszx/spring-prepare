-- =====================================================================
--  V5__sessions.sql — 로그인 세션 (M6-2c)
-- =====================================================================
--  토큰은 랜덤 문자열 + DB 행이다 (M6 계획서 §3-3). JWT 를 쓰지 않는 이유:
--  PLAN "하지 않는 것" — 서명·만료·갱신의 복잡함 없이, 행이 있으면 유효하고
--  지우면 무효라는 가장 단순한 모델로 인증을 배운다. 로그아웃 = DELETE 한 줄.
--
--  token 이 PK 다. 조회가 항상 "이 토큰이 유효한가" 이므로 토큰이 곧 키다.
--  CHAR(64) = SecureRandom 32바이트의 hex. 고정 길이라 VARCHAR 가 아니라 CHAR.
--
--  expires_at 은 DATETIME 이고 값은 **UTC 다** (D-009). 쓰기는 앱이
--  UtcTime.toDbValue 로 정규화해 넣고, 만료 판정도 UTC 끼리 비교한다.
--  created_at 은 DB DEFAULT — M0 부터의 규칙 그대로.
--
--  user_id 의 인덱스는 FK 가 자동으로 만든다 (지탱할 선두 인덱스가 없으므로).
--  만료 행 청소는 두지 않는다 — 행이 남아 있어도 만료 확인이 막고,
--  이 규모에서 청소 배치는 배보다 배꼽이다. 필요해지면 그때 결정한다.
-- =====================================================================

CREATE TABLE sessions (
    token      CHAR(64)   NOT NULL PRIMARY KEY,
    user_id    BIGINT     NOT NULL,
    expires_at DATETIME   NOT NULL,                              -- UTC (D-009)
    created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,    -- UTC (세션 time_zone, D-009)
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
);
