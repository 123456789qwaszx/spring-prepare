# 결정 기록 (Decision Log)

> 형식은 ADR(Architecture Decision Record)의 축약판이다. 한 결정 = 한 절.
> **배경 → 선택지 → 결정 → 결과(따라오는 일)** 순서로 쓴다. 나중에 뒤집더라도 지우지 않고 "대체됨"으로 표시한다.
> PLAN.md §5 "열린 결정 목록"의 번호는 `PLAN#n`으로 표기한다.

숙련자가 결정을 기록하는 이유는 두 가지다. (1) 두 달 뒤의 자신이 "왜 이렇게 했지?"를 다시 조사하지 않게. (2) 결정을 뒤집을 때 **그때 몰랐던 것**이 무엇인지 분명히 하기 위해.

---

## D-001. Spring Boot 4.1.1로 복귀 (3.1.0에서)

- 상태: **결정됨** (2026-08-28, 아미야)
- 배경: `build.gradle`은 3.1.0, PLAN.md는 4.1.1 전제. 3.1.0에는 `JdbcClient`가 없고, 3.x는 OSS 지원 종료, Gradle 9.5.1 wrapper와 호환 불확실. (ANALYSIS §3.1)
- 선택지:
  1. **4.1.1 복귀** — PLAN·init 커밋과 일치, `JdbcClient` 사용, Gradle 9 호환, 지원 중. 대신 4.x 스타터 이름과 Jackson 3 패키지가 3.x 강의 자료와 다르다.
  2. 3.1.0 유지 — 강의 자료와 동일. `JdbcClient` 대신 `NamedParameterJdbcTemplate`로 PLAN을 고쳐야 하고 wrapper를 8.x로 내려야 할 가능성.
  3. 3.5.x — `JdbcClient` 사용 가능, 3.x 자료와 가까움. 그러나 이미 EOL.
- 결정: **1. 4.1.1 복귀.**
- 판단 근거(사고흐름): 학습 프로젝트라도 "지원이 끝난 버전으로 새로 시작"은 피한다. 강의 자료와의 차이는 스타터 이름·패키지 몇 개 수준이고, 그 차이를 아는 것 자체가 학습이다. 반면 `JdbcClient` 부재는 PLAN의 레포지토리 규약 전체를 바꾸는 큰 차이다. **작은 차이를 감수하고 큰 차이를 피한다.**
- 결과:
  - `build.gradle`: 플러그인 4.1.1, `starter-webmvc`, `starter-webmvc-test`. Thymeleaf·Lombok 유지.
  - 3.x 강의를 볼 때의 번역표: `starter-web→starter-webmvc`, `starter-test→starter-webmvc-test`, `com.fasterxml.jackson.databind.*→tools.jackson.databind.*`, `@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure`, `@MockBean→@MockitoBean`.
  - 첫 빌드에서 의존성 해석 실패 시 추측으로 이름을 바꾸지 않는다 (PLAN §2.2).

## D-002. 통합 테스트 DB = 로컬 MySQL `game_test` (PLAN#6)

- 상태: **결정됨** (2026-08-28, 아미야)
- 배경: PLAN §2.6은 Testcontainers 도입 여부를 M0에서 결정하라고 한다.
- 선택지: (1) 로컬 MySQL `game_test` (2) Testcontainers (Docker 필요) (3) H2 인메모리.
- 결정: **1.** Docker 의존을 만들지 않고, MySQL 고유 문법(`ON DUPLICATE KEY UPDATE`, JSON 컬럼)을 실제 엔진에서 테스트한다. H2는 이 프로젝트의 학습 목표(MySQL 문법·EXPLAIN)와 정면으로 어긋나 제외.
- 결과:
  - `src/test/resources/application-test.properties` (gitignore) + `application-test.properties.example` (커밋).
  - 테스트 클래스는 `@ActiveProfiles("test")`.
  - `game_test`에 schema.sql 적용은 수동 (M0-check.md). Flyway 도입(PLAN#5, M6) 시 자동화 재검토.
  - 정리는 TRUNCATE가 아니라 자식→부모 순 DELETE (ANALYSIS §3.5).

## D-003. 코드 전달 = 로컬 클론 폴더에 직접 쓰기, 빌드·커밋은 아미야

- 상태: **결정됨** (2026-08-28, 아미야)
- 배경: 클라우드 작업 공간에서 Maven Central·GitHub push 불가. (ANALYSIS §1.4)
- 결정: Claude가 파일을 `C:\Users\river\Documents\GitHub\spring-prepare`에 써넣고, 아미야가 IntelliJ에서 빌드·실행·커밋.
- 결과: 계획서의 작업 상태는 `작성됨`(파일 반영) / `검증됨`(아미야가 완료 기준 통과 확인)을 구분한다. 커밋 메시지는 PLAN §0 형식(`M0: 접속 확인`).

## D-004. M0에서 서비스 계층과 에러 응답 형식을 미리 세운다

- 상태: **결정됨** (2026-08-28, Claude 제안 → M0 계획서에 명시, 이견 있으면 되돌림)
- 배경: PLAN M0 작업 목록은 `User`, `UserRepository`, `UserController`, `GlobalExceptionHandler`만 적었다. 그러나 §2.4 규약은 Controller→Service→Repository, §2.5는 트랜잭션 경계가 Service다. 에러 응답 통일(`{code, message, detail?}`)은 M6 항목이다.
- 선택지: (1) M0는 최소로 — Controller가 Repository 직접 호출, 핸들러는 문자열 응답 (2) 규약대로 Service를 두고 에러 응답 record를 지금부터 쓴다.
- 결정: **2.** 얇더라도 Service를 둔다. 에러 응답은 `ErrorResponse(code, message, detail)` record를 지금 만들고, M6에서는 **누락 케이스를 채우는 일**만 남긴다.
- 판단 근거: 규약은 첫 코드에서 세워야 지켜진다. M1부터 트랜잭션이 Service에 붙는데 M0만 다른 모양이면 "M0는 예외"라는 습관이 생긴다. 반면 M0의 "하지 않는 것"(로그인·해시·토큰)은 그대로 하지 않는다 — 이것은 범위 확장이 아니라 **형태 통일**이다.
- 결과: `common/ErrorResponse`, `common/NotFoundException`, `common/BadRequestException`, `common/GlobalExceptionHandler`. `DataIntegrityViolationException`(NOT NULL·길이·FK 위반) → 400, 그 하위인 `DuplicateKeyException` → 409. 핸들러 선택은 예외 계층상 가장 가까운 것이 이긴다.

## D-005. M0 입력 검증은 수동 `if`로, Validation 스타터는 넣지 않는다

- 상태: **결정됨** (2026-08-28)
- 배경: PLAN §2.2 "M6 전까지 추가 의존성 금지". `username` 공백·30자 초과는 DB에서 잡히지만 그때는 500 또는 400(D-004)이고 메시지가 불친절하다.
- 결정: Service에서 `username`/`password` 공백 검사만 `if`로 하고 `BadRequestException` → 400. 길이 초과는 DB 제약(`VARCHAR(30)`)에 맡겨 `DataIntegrityViolationException` → 400으로 본다 — "DB 제약이 애플리케이션 밖에서 동작한다"는 M0 목표를 눈으로 보기 위해 **일부러** 앱에서 막지 않는다.
- 결과: M6에서 `spring-boot-starter-validation` 도입 여부 재검토.

---

## 열린 결정 (PLAN §5에서 아직 안 정한 것)

| PLAN# | 결정 | 기본 권장 | 결정 시점 | 상태 |
|---|---|---|---|---|
| 1 | `body` 컬럼 JSON vs LONGTEXT | JSON, diff는 의미 비교 | M1 착수 | 미결 |
| 2 | 슬롯 개수 제한 | 3 | M2 착수 | 미결 |
| 3 | 요청 UUID 멱등 키 | choices 기반 시작 | M4 착수 | 미결 |
| 4 | `event_log` UNIQUE 범위 | (a) 회차 내 1회 | M6 | 미결 |
| 5 | Flyway | M6 도입 | M6 | 미결 |
| 6 | Testcontainers | — | M0 | **D-002로 해소** |
