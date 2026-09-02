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
- **2026-08-29 (M4) 갱신 — `detail` 을 넓히지 않기로 했다.** M4 의 409 는 서버 슬롯 상태(**객체**)를 실어야 하는데 `ErrorResponse.detail` 은 `String` 이다. 선택지는 (a) `detail` 을 `Object` 로 넓힌다 (b) `ConflictResponse` 를 따로 둔다. **(b)** 를 골랐다 — 한 곳의 필요 때문에 **나머지 모든 에러 응답의 계약을 느슨하게 만들지 않는다.** 대가는 에러 형식이 둘이 된다는 것이고 M6 에서 통일한다. `code`·`message` 는 같은 이름으로 맞춰 두어 클라의 분기 방식은 바뀌지 않는다.
- **2026-08-30 (M6 착수 전) 갱신 — "통일"의 의미를 확정했다.** M6 의 에러 형식 통일은 **단일 record 로 합치는 것이 아니라 공통 계약을 세우는 것**이다: 모든 4xx/5xx 는 `code`(클라 분기 키)와 `message` 를 가진다. 형식은 둘뿐이다 — `ErrorResponse{code, message, detail?:String}` 와 `ConflictResponse{code, message, current}`. M6 계획서 초안의 "409 요약은 `detail` 에" 는 이 갱신(M4) **이전**의 문장이 남아 있던 것이라 삭제했다 — `detail` 을 `Object` 로 넓히는 (a)안을 다시 여는 것이 아니다. M6 완료 기준도 같은 말로 고쳤다 (M6.md §7).

## D-005. M0 입력 검증은 수동 `if`로, Validation 스타터는 넣지 않는다

- 상태: **결정됨** (2026-08-28)
- 배경: PLAN §2.2 "M6 전까지 추가 의존성 금지". `username` 공백·30자 초과는 DB에서 잡히지만 그때는 500 또는 400(D-004)이고 메시지가 불친절하다.
- 결정: Service에서 `username`/`password` 공백 검사만 `if`로 하고 `BadRequestException` → 400. 길이 초과는 DB 제약(`VARCHAR(30)`)에 맡겨 `DataIntegrityViolationException` → 400으로 본다 — "DB 제약이 애플리케이션 밖에서 동작한다"는 M0 목표를 눈으로 보기 위해 **일부러** 앱에서 막지 않는다.
- 결과: M6에서 `spring-boot-starter-validation` 도입 여부 재검토.

## D-006. `chapter_contents.body`는 MySQL `JSON` 타입을 유지한다 (PLAN#1)

- 상태: **결정됨** (2026-08-29, 아미야)
- 배경: PLAN §5 열린 결정 #1. 실측하니 샘플(`qwer.progression.json`) 5,686바이트가 `JSON` 컬럼에서 약 3,240바이트로 줄어든다 — 공백·들여쓰기 제거와 키 순서 정규화. 즉 **바이트 동일 보존은 불가능**하다.
- 선택지:
  1. **`JSON` 유지** — 잘못된 JSON을 DB가 거부(검증을 공짜로), M5의 `JSON_EXTRACT`/`JSON_TABLE`이 자연스러움, 저장 공간 절약.
  2. `LONGTEXT`로 변경 — 바이트 그대로. 대가: M5마다 `CAST(body AS JSON)`, 인덱스 불가, 잘못된 JSON도 저장됨, 마이그레이션 필요.
- 결정: **1. `JSON` 유지.**
- 판단 근거: 클라(Unity)는 이 파일을 파싱해서 쓰므로 바이트 동일이 필요 없다. 반면 M5의 집계는 `body`를 JSON으로 다루는 것이 전제다. **누가 이 데이터를 어떻게 쓰는지**가 컬럼 타입을 정한다 — "원본 보존"이라는 말의 어감이 아니라.
- 결과:
  - PLAN M1 완료 기준의 "diff 0"을 **파싱 후 트리 비교**로 바꾼다. 테스트 `내려받은_본문은_바이트는_달라도_의미는_같다()`가 그것이다.
  - `checksum`은 **업로드 원본 바이트** 기준이라 다운로드본을 다시 해싱하면 값이 다르다. schema.sql 주석대로 재수입 방지 전용이며, 클라의 무결성 검증에는 쓸 수 없다. 클라가 "서버 것이 바뀌었나"를 알아야 하면 `version`을 본다.
  - M2의 `save_slots.snapshot`도 같은 정책을 따른다 (같은 JSON 컬럼).

## D-007. `game_definitions`에 `checksum` 컬럼을 마이그레이션으로 추가한다

- 상태: **결정됨** (2026-08-29, 아미야)
- 배경: PLAN M1은 `POST /content/definition`이 재수입 시 200을 반환하라고 하는데, `schema.sql` v1의 `game_definitions`에는 `checksum` 컬럼이 없다(`chapter_contents`에는 있다). **PLAN.md와 schema.sql이 어긋난 자리**다. 실물 `game.definition.json`도 아직 Unity 레포에 없다.
- 선택지: (1) 마이그레이션으로 컬럼 추가 (2) definition은 멱등성 없이(매번 version++) (3) definition을 M1에서 제외.
- 결정: **1.** `db/migrations/V2__gamedef_checksum.sql`.
- 판단 근거: 같은 개념(콘텐츠 재수입)에 두 규칙을 두면 나중에 어느 쪽이 맞는지 매번 확인해야 한다. 그리고 PLAN §3이 정한 "스키마 변경은 마이그레이션으로"를 여기서 처음 실천한다 — 규칙은 처음 쓸 때 세워야 지켜진다. 실물 파일이 없어도 구현에 지장이 없는 이유는 **서버가 definition을 해석하지 않기 때문**이고(PLAN 1.4), 그 사실이 설계가 맞았다는 신호이기도 하다.
- 결과:
  - `schema.sql`은 고치지 않는다. 새 환경은 `schema.sql` → `V2__…` 순서.
  - `game`과 `game_test` 양쪽에 수동 적용 필요 → PLAN#5(Flyway) 근거 축적.
  - M5의 첫 인덱스 마이그레이션은 `V2`가 아니라 **`V3`**가 된다.

## D-008. 슬롯 개수 상한을 서버가 정하지 않는다 — 번호의 유효 범위만 보장 (PLAN#2)

- 상태: **결정됨** (2026-08-29, 아미야)
- 배경: PLAN §5 열린 결정 #2는 "슬롯 개수 제한, 기본 권장 3"이었다.
- 아미야의 결정과 근거(원문): *"서버는 슬롯 번호의 유효 범위만 보장하고, 최대 슬롯 개수는 클라이언트 정책으로 둔다. VN 특성상 다수의 수동 세이브를 허용할 수 있으므로 3개 같은 작은 고정 상한은 두지 않는다."*
- 선택지: (1) 서버가 3개로 제한 (2) **서버는 범위만, 개수는 클라 정책** (3) 아무 검사도 하지 않음.
- 결정: **2.**
- 판단 근거: 슬롯 개수는 **게임 디자인**이지 데이터 무결성이 아니다. 서버가 3으로 박으면 클라가 세이브 UI를 바꿀 때마다 서버 배포가 필요해진다. 반면 "번호가 컬럼에 담기는가"는 데이터 무결성이라 서버가 지켜야 한다. **서버는 지켜야만 하는 것만 지킨다** — PLAN 1.4의 "서버는 판정하지 않는다"와 같은 선긋기다.
- 결과:
  - `SaveSlotService`의 범위는 `1 ~ 127` (`save_slots.slot_no`이 `TINYINT`). 상수 두 개로 두고 이유를 주석에 적었다.
  - 앱이 먼저 400으로 거르는 이유: 범위를 넘기면 DB는 "Out of range" 오류(500 계열)를 내는데, 클라에게 맞는 답은 "번호가 잘못됐다"(400)이다. M0의 `BAD_REQUEST` / `CONSTRAINT_VIOLATION` 구분과 같은 층위.
  - **완료 기준이 바뀐다.** PLAN M2의 "슬롯 4 → 400"은 3개 상한을 전제한 기준이라 이 결정과 모순된다. 두 가지로 대체:
    - 슬롯 `0` 또는 `128` → 400 (범위 밖)
    - 슬롯 `1, 5, 42, 127`을 모두 만들 수 있다 (상한 없음의 증명)
  - 127이 부족해지면 `slot_no`를 `SMALLINT`로 넓히는 한 줄 마이그레이션이면 된다 — 넓히는 방향이라 기존 데이터에 무해하다. 지금은 넓히지 않고 기록만 한다.

## D-009. 영속 시각은 UTC로 통일한다 (ANALYSIS §3.8, M3)

- 상태: **결정됨** (2026-08-29, 아미야)
- 배경: M0에서 `created_at`이 KST로 정확히 기록된 것을 확인했다(F8). 즉 지금 DB의 `DATETIME`은 **KST 벽시계를 담고 있었다.** M3에서 클라가 `chosenAt`을 직접 보내기 시작하면서 이 전제가 처음으로 흔들린다 — 오프라인 플레이 시각은 기기의 시간대로 찍히고, 여러 기기가 붙으면 시간대가 여러 개가 된다.
- 아미야의 결정과 근거(원문): *"DB에 저장되는 모든 절대 시각은 UTC로 해석한다. 클라이언트가 전달하는 시각은 ISO-8601 UTC(Z) 형식을 사용한다. 지역 시간(KST 등)은 표시 시점에만 변환한다. 기존 개발용 KST 데이터는 폐기하고 UTC 기준으로 재생성한다."*
- 선택지: (1) KST 유지 (2) **전부 UTC, 표시할 때만 변환** (3) 컬럼을 `TIMESTAMP`로 바꿔 드라이버에 맡김.
- 결정: **2.**
- 판단 근거:
  - **저장은 순간(instant)이고, 시간대는 표현이다.** 표현을 저장하면 나중에 순간을 복원할 수 없다 — `2026-08-29 20:40:19`만 남으면 그게 어디의 20시인지 아무도 모른다.
  - 3(=`TIMESTAMP`)이 더 "정석"으로 보이지만 스키마 전체를 바꿔야 하고, `TIMESTAMP`는 2038년 상한이 있으며, **드라이버가 세션 시간대에 따라 조용히 변환**한다. 눈에 안 보이는 변환은 학습에도 운영에도 나쁘다. `DATETIME` + "여기 든 것은 UTC다"라는 한 줄 규칙이 더 명시적이다.
  - 서버는 클라의 시간대를 알 필요가 없다. KST 표시는 화면의 일이다 — PLAN 1.4의 선긋기와 같다.
- 결과:
  - **접속 URL이 바뀐다** (`application-local.properties`, `application-test.properties` — 둘 다 gitignore이므로 아미야가 직접 고친다):
    `...?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`
    - `serverTimezone`은 `connectionTimeZone`의 옛 이름(별칭)이다. 새 이름으로 쓴다.
    - `forceConnectionTimeZoneToSession`의 **기본값은 `false`**다. 이것을 켜지 않으면 세션 `time_zone`이 서버 기본(KST)으로 남아, DB DEFAULT `CURRENT_TIMESTAMP`가 채우는 `created_at`은 KST로, 앱이 넣는 `chosen_at`은 UTC로 들어가 **한 테이블 안에서 시간대가 갈린다.**
  - **경계 변환 지점을 코드로 만든다**: `common/UtcTime.toDbValue(OffsetDateTime)`. Connector/J의 읽기·쓰기 비대칭 때문에 필요하다 (§아래).
  - 앱이 다루는 타입: **읽기 `OffsetDateTime`, 쓰기 `LocalDateTime`(UTC 벽시계)**. 응답 JSON은 `2026-08-29T11:40:19Z`로 나간다 — 클라가 시간대를 짐작할 자리가 없다.
  - **기존 개발용 데이터는 폐기한다.** `game`의 KST 시각과 새 UTC 시각이 섞이면 어느 행이 어느 규칙인지 알 수 없다. M3-check.md의 1단계가 이 정리다.
  - 이 결정은 M0~M2의 8개 record를 `LocalDateTime` → `OffsetDateTime`으로 바꾸게 했다: `User`, `ChapterContent`, `ChapterVersionInfo`, `Playthrough`, `PlaythroughSummary`, `PlaythroughEndResponse`, `SaveSlotSummary`, `SaveSlotDetail`.

### 왜 코드에 변환 지점이 필요한가 (Connector/J의 비대칭)

Connector/J 9.7.0 개발자 가이드 "Preserving Time Instants":

| 방향 | 대상/원본 | 변환 여부 |
|---|---|---|
| **쓰기** | 대상이 `DATETIME` | **하지 않는다.** `OffsetDateTime`을 넘겨도 벽시계 부분만 저장된다 |
| **쓰기** | 대상이 `TIMESTAMP` | 한다 |
| **읽기** | 원본이 `DATETIME` → `OffsetDateTime` 등 순간 타입 | **한다.** 연결 시간대로 해석 |

즉 `2026-08-29T20:40:19+09:00`을 그대로 넘기면 **20:40:19이 저장**되고, 그것을 UTC로 읽으면 9시간 어긋난다. 그래서 쓰기 직전에 우리가 UTC로 정규화한다(`UtcTime.toDbValue`). 읽기는 드라이버에 맡기되, **드라이버에 맡긴다는 것은 URL이 바뀌면 조용히 틀린다는 뜻**이므로 `SaveHistoryApiTest`가 `+09:00`을 보내 DB의 벽시계 문자열까지 직접 확인한다.

## D-010. 재전송 판정은 동봉 `choices` 기반, 판정 불가면 409 (PLAN#3)

- 상태: **결정됨** (2026-08-29, 아미야)
- 배경: M4는 두 가지를 구별해야 하는데 `revision` 만으로는 똑같이 보인다.
  - **재전송**: 클라가 보냈고 서버는 적용했는데 응답이 유실됐다 → 옳은 답은 `200`
  - **충돌**: 그 사이 다른 기기가 썼다 → 옳은 답은 `409`

  M3에서 같은 seq 재전송이 `409 {"code":"DUPLICATE"}` 로 끝나는 것을 확인했다. M4는 이 409를 갈라내는 M이다.
- 선택지:
  1. **동봉 `choices` 기반 판정. 판정 불가(= `choices` 가 빔)면 409 + 현재 상태.**
  2. 동봉 `choices` 기반 판정. 판정 불가면 200 + 현재 상태. — **PLAN 원문의 권장**
  3. 요청 UUID(멱등 키)를 지금 도입.
- 결정: **1.**
- 판단 근거:
  - **M4의 학습 핵심이 그대로 살아난다.** PLAN이 말하는 "락이 아니라 데이터(UNIQUE, revision)로 푼다"가 곧 `choices` 기반 판정이다. UUID를 먼저 넣으면 UNIQUE 가 재전송을 흡수하는 장면을 건너뛰게 된다.
  - **2의 구멍은 가정이 아니라 실측이다.** M3 §4.8에서 `choices` 없는 PUT(세이브만 올리기)이 **정상 경로**임을 확인했다 — 자동 저장이 `playSeconds` 만 갱신하는 경우가 그것이다. 그때 200을 주면 **충돌을 재전송으로 오인**하고, 클라는 다른 기기가 덮은 줄 모른 채 "동기화됐다"고 믿는다. 서버가 틀린 200을 말하는 것보다 불필요한 409가 낫다.
  - **서버는 확실할 때만 판정하고, 애매하면 사실을 넘긴다.** 409 본문에 현재 서버 상태를 실어 주면 재전송한 클라는 자기가 보낸 것과 비교해 스스로 판정할 수 있다. PLAN 1.4의 "서버는 판정하지 않는다"와 같은 선긋기다 — 판정의 종류만 다르다.
  - **3은 정확하지만 지금 대가가 크다.** Flyway 전(M6)이라 손 마이그레이션이 하나 더 늘고, 무엇보다 **재전송 시 같은 UUID를 유지해야 하는 클라가 아직 없다**(M7). 아직 만들지 않은 클라의 제약을 지금 못 박을 이유가 없다.
- 결과:
  - 응답: `200 {replayed:true, ...}` / `409 + 현재 서버 상태 요약`. 409 본문 필드가 M8 충돌 UI가 보여줄 것과 같다.
  - 판정 순서(M4-4): 현재 슬롯 SELECT → **replayed 판정** → 조건부 UPDATE(0행이면 409) → 이력 배치. 판정이 UPDATE 보다 앞이어야 replayed 응답에서 revision 이 오르지 않는다(C4).
  - replayed 조건: `서버 revision == baseRevision + 1` **그리고** 동봉 `(save_slot_id, seq)` 가 **전부 이미 존재**. 하나라도 새 것이면 재전송이 아니다.
  - `choices` 가 비면 판정하지 않고 조건부 UPDATE 로 넘어간다 → 낡은 base 면 자연히 409 가 난다. **별도 분기를 두지 않는다** — "판정 불가"를 코드가 특별 취급하지 않아도 결과가 맞는다.
  - **재검토 방아쇠**: M7에서 클라를 만들 때 빈-`choices` 재전송의 409 가 실제로 불편하면, UUID(3)를 M6 Flyway 마이그레이션과 함께 붙인다. 근거가 쌓인 뒤에 — Flyway 도입이 M0→M1에서 근거를 모아 결정된 것과 같은 방식이다.

## D-011. `event_log` UNIQUE 를 "회차 내 EventKey당 1회" 로 좁힌다 (PLAN#4)

- 상태: **결정됨** (2026-08-30, 아미야)
- 배경: v1 스키마의 `uk_event_once (playthrough_id, event_key, chapter_content_id, episode_id)` 에는
  **`chapter_content_id` 가 들어 있다.** 챕터를 개편해 v2 를 올리면 같은 회차에서 `ENDING_A` 가 **또** 기록된다.
  M5 의 도달률 쿼리에 `COUNT(DISTINCT playthrough_id)` 가 있는 이유가 이것이다.
- 선택지: (1) **`(playthrough_id, event_key)`** (2) 현행 유지 (3) `(playthrough_id, event_key, chapter_content_id)`
- 결정: **1.**
- 판단 근거:
  - `event_log` 는 **[1] 영구 계층**이고, 영구 계층이 답하는 질문은 "이 회차에서 무슨 일이 있었나" 다.
    **"어느 버전에서 봤나" 는 콘텐츠 창고의 관심사**이지 영구 계층의 관심사가 아니다.
  - 플레이어 입장에서도 챕터가 개편됐다고 엔딩을 다시 봐야 하는 것은 이상하다.
    해금 판정은 클라가 `event_log` 를 근거로 하는데(PLAN 1.4), 버전별로 쪼개져 있으면 클라가 매번 합쳐야 한다.
  - 3 은 중간이라 어느 쪽 장점도 온전히 얻지 못한다.
- 결과:
  - **`V4__event_once_per_playthrough.sql`**: 기존 UNIQUE 드롭 → `(playthrough_id, event_key)` 로 재생성.
    이름은 `uk_event_once` 를 그대로 쓴다 — "회차에서 한 번" 이라는 이름이 오히려 새 정의에 더 맞는다.
  - **컬럼은 남긴다.** UNIQUE 에서 빼는 것이지 지우는 것이 아니다.
    "어느 버전의 어느 에피소드에서 **처음** 봤나" 는 여전히 유용하고, M5 의 JOIN 이 그것을 쓴다.
  - **마이그레이션 전에 중복을 확인해야 한다.** 기존 데이터에 `(playthrough_id, event_key)` 중복이 있으면 실패한다:
    `SELECT playthrough_id, event_key, COUNT(*) FROM event_log GROUP BY 1,2 HAVING COUNT(*) > 1;` → 0행이라야 한다.
  - M5 의 `COUNT(DISTINCT playthrough_id)` 는 **그대로 둔다.** 이제 중복이 불가능해졌지만,
    쿼리가 제약에 기대지 않는 편이 낫다 (`event_reach.sql` 주석에 이미 그렇게 적혀 있다).
  - **파생 효과 하나 — 409 가 더 자주 난다.** 버전을 올린 뒤의 재도달이 이제 막힌다.
    지금 `EventLogRepository.insertAll` 은 중복이면 `DuplicateKeyException` → **요청 전체가 409** 다.
    M4 가 choices 재전송을 `replayed` 로 흡수했듯, **이벤트도 "이미 있으면 빼고 넣기" 가 필요해진다** → M6 작업 목록.
    (기존 `existingEpisodeIds` 는 `(playthrough, content, episode)` 기준이라 새 UNIQUE 와 맞지 않는다 —
     `existingEventKeys(playthroughId, keys)` 가 필요하다.)
  - **2026-08-30 (M6 착수 전) 갱신 — V4 는 한 문장이어야 한다.** `uk_event_once` 는
    `fk_event_playthrough`(playthrough_id FK) 를 지탱하는 **유일한 선두 인덱스**다
    (FK 용 자동 인덱스는 지탱할 인덱스가 이미 있으면 만들어지지 않는다). DROP 을 별도 문장으로 쓰면
    1553(`Cannot drop index: needed in a foreign key constraint`) 이 난다. 새 UNIQUE 도
    playthrough_id 선두라 **한 문장 안에서 교체하면 FK 가 끊길 틈이 없다**:
    `ALTER TABLE event_log DROP KEY uk_event_once, ADD UNIQUE KEY uk_event_once (playthrough_id, event_key);`
    V3 주석의 "FK 가 쓰는 인덱스였다면 얘기가 다르다" 가 여기서 현실이 됐다.

## D-012. Flyway 를 도입하고 `game` 을 다시 만든다 (PLAN#5)

- 상태: **결정됨** (2026-08-30, 아미야)
- 배경: 근거가 **셋** 쌓였다.
  - **M0**: `schema.sql` 을 `game`·`game_test` 에 손으로 두 번 적용.
  - **M1**: 그 수작업이 실제로 터졌다 (ANALYSIS §4.1, R4) — `game` 에 테이블이 `users` 하나뿐이었고
    M0 는 `users` 만 써서 완료 기준을 전부 통과하고도 드러나지 않았다. M1 의 `ALTER TABLE` 에서야 `Error 1146`.
    게다가 `schema.sql` 은 `IF NOT EXISTS` 가 없어 **재실행이 불가능**했다(F14).
  - **M5**: `V3__stats_indexes.sql` 을 다시 두 DB 에 손으로. 마이그레이션이 둘이 되면서
    **"어디까지 적용했나" 를 사람이 기억해야 하는 상태**가 됐다.
- 선택지(도입 여부 + 기존 `game` 처리): (1) **도입 + `game` 재생성** (2) 도입 + `baseline-version=3` (3) 도입 안 함
- 결정: **1.**
- 판단 근거:
  - `baseline` 은 **"여기까지는 적용됐다고 믿어라"** 다. 검증이 아니라 신뢰다.
    **M1 의 R4 가 정확히 "믿었는데 아니었던" 사고**였고, baseline 은 같은 종류의 신뢰를 요구한다.
    믿을 근거가 부족해서 도구를 들이는 마당에, 도구를 들이는 첫 단계에서 다시 믿는 것은 앞뒤가 안 맞는다.
  - **재생성의 비용이 거의 없다.** `db/seed.sql` 이 있어 데이터 복구가 한 번의 실행이다.
    M5 에서 seed 를 만든 것이 여기서 값을 한다 — 그때는 집계 학습용이었는데 지금은 **환경 재현 수단**이다.
  - 그러고 나면 `game` 과 `game_test` 가 **같은 경로로 만들어진 상태**가 된다. 그것이 드리프트가 없다는 뜻이다.
- 결과:
  - 의존성: `org.flywaydb:flyway-core` + **`org.flywaydb:flyway-mysql`** (Flyway 9+ 부터 MySQL 은 별도 모듈).
    Boot 은 `spring-boot-starter-flyway` 라는 이름을 쓰지 않는다 — 첫 빌드에서 이름을 추측하지 않는다(PLAN §2.2).
  - **위치를 옮긴다**: `db/migrations/` (프로젝트 루트) → **`src/main/resources/db/migration/`** (Flyway 기본 경로, 단수).
    클래스패스라야 jar 에 포함돼 배포에서도 동작한다. PLAN §3 이 정한 경로와 다르지만
    **PLAN 은 고치지 않고 이 기록이 해석을 담당한다** (D-006·D-008 과 같은 방식).
    `db/seed.sql` 은 마이그레이션이 아니므로 `db/` 에 남는다.
  - **`spring.flyway.encoding=UTF-8` 을 명시한다** (M5 §7-1 F35). 기본값에 맡기면 한국어 Windows 에서
    MS949 로 읽어 한글이 깨진 채 들어가고, **SQL 문법도 행 수도 멀쩡해서 조용히 통과한다.**
  - 마이그레이션 순서: `V1__init.sql`(= `schema.sql` 그대로) → `V2__gamedef_checksum.sql` →
    `V3__stats_indexes.sql` → `V4__event_once_per_playthrough.sql`(D-011).
    `schema.sql` v1 에는 V2·V3 의 변경이 없으므로 그대로 옮겨도 순서가 맞는다.
  - 절차: `game` drop → create → 앱 기동(Flyway 가 V1~V4 적용) → `seed.sql` 실행. `game_test` 도 같은 방식.
  - `docs/schema.sql` 은 **DDL 정본으로 남긴다.** 읽기용이고, 적용은 `V1__init.sql` 이 한다.
    두 파일이 같은 내용을 담게 되므로 **어느 쪽이 정본인지 M6-check 에 적어 둔다.**
- **2026-08-30 (M6 착수 전) 갱신 둘.**
  - **(1) V1 은 schema.sql "그대로"가 아니다** — 머리의 `CREATE DATABASE IF NOT EXISTS game;` / `USE game;`
    두 줄을 **빼고** 옮긴다. 남기면 Flyway 가 `game_test` 를 마이그레이션하다 `USE game` 으로 갈아타
    테이블은 전부 `game` 에 생기고 history 만 `game_test` 에 남는다 — R4 류의 조용한 드리프트를
    도구 도입 첫날 만드는 셈이다. `seed.sql` 에 스키마 이름이 없는 것과 같은 이유다.
    DB 자체는 사람이 만드므로 `CREATE DATABASE … CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci` 는
    M6-check 의 재생성 절차로 옮긴다. "재실행 가능성 검토" 는 하지 않는다 — 한 번만 적용되는 것이
    마이그레이션의 정의고, 그 한 번은 Flyway 가 보장한다.
  - **(2) 마이그레이션 파일을 전부 쓴 뒤에 재생성한다.** 초안의 작업 순서는 재생성(M6-1b)이
    V4·V5 작성보다 앞이라 같은 표의 "기동 시 V1~V5 적용" 과 모순이었다.
    **파일 전부(V1 이동·V4·V5) → 재생성 한 번 → seed** 로 바로잡았다 (M6.md §4).
    이 순서라야 V4 가 언제나 **빈 DB** 에 적용돼 기존 데이터 걱정이 없고, 기동도 한 번이다.
- **2026-08-30 (M6 구현, 소스 대조) 갱신 둘 더.**
  - **(3) Boot 4 에는 `spring-boot-starter-flyway` 가 있다.** "그런 스타터는 없다"(M6 계획서 초안의
    자기 교정)는 Boot 3 까지의 사실이었고, 4.0 모듈화 때 생겼다 — boot 레포 v4.1.1 의
    `starter/spring-boot-starter-flyway` 로 확인. 모듈화 때문에 이 스타터가 이제 **필요**하기도 하다:
    자동 구성이 `spring-boot-autoconfigure` 한 덩어리가 아니라 `spring-boot-flyway` 모듈에 살고,
    `flyway-core` 만 넣으면 그 모듈이 안 따라온다. 최종 좌표:
    `implementation 'org.springframework.boot:spring-boot-starter-flyway'` + `runtimeOnly 'org.flywaydb:flyway-mysql'`
    (관리 버전 Flyway 12.4.0). **"이름을 지어내지 않는다"의 교훈이 한 번 더 뒤집힌 자리** —
    지난번엔 지어낸 이름이 없어서 틀렸고, 이번엔 없다고 믿은 이름이 생겨서 틀렸다.
    양쪽 다 소스 대조가 잡았다.
  - **(4) `spring.flyway.encoding` 의 기본값은 이미 UTF-8 이다** (Boot 의 `FlywayProperties` 소스
    `private Charset encoding = StandardCharsets.UTF_8` 로 확인). "기본값에 맡기면 MS949"는
    `@SqlConfig` 의 이야기(F35)였고 Flyway 에는 해당하지 않는다 — **F35 를 Flyway 로 일반화한 것이
    과했다.** 명시는 그대로 유지한다: "이 파일들은 UTF-8" 을 설정에 적어 두는 값이 있고,
    기본값이 바뀌어도 안전하다. 다만 근거를 "위험 회피"에서 "의도 문서화"로 고쳐 적는다.

## D-013. `/stats/**` 는 관리자 키로 보호한다

- 상태: **결정됨** (2026-08-30, Claude 결정 — 아미야가 판단 위임. 이견 있으면 되돌림)
- 배경: M6 초안의 보호 범위는 토큰(`/playthroughs/**`, `/users/{id}/**`)과 관리자 키(`/content/**` POST)뿐이다.
  `GET /stats/events` 와 `GET /stats/chapters/{id}/choices` 는 어느 쪽에도 안 걸려 **M6 이 끝나도 완전 공개**로
  남는다. 그런데 STATUS 6차 점검은 집계 API 를 "클라가 쓰지 않는다(관리자용)" 로 정리했고,
  M9-3 관리자 화면도 `X-Admin-Key` 를 전제한다.
- 선택지: (1) **지금 `X-Admin-Key` 로 묶는다** (2) 토큰으로 묶는다 (3) M9 까지 공개로 두고 기록만.
- 결정: **1.**
- 판단 근거:
  - M6 의 목표가 "Unity 클라가 붙어도 되는 상태" 인데, 클라 계약에 없는 엔드포인트가 무인증으로
    열려 있는 것은 그 정의와 어긋난다. 마감 M 에서 잡는 것이 맞다.
  - 2(토큰)는 "로그인한 아무 유저나 전체 통계를 본다" 가 되어 용도와 안 맞는다.
    유저용 요약(`/users/{id}/summary`)은 토큰 경로에 이미 들어가 본인 것만 보게 된다 —
    유저용과 관리자용이 자연스럽게 갈린다.
  - 비용이 거의 없다 — M6-7 의 `AdminKeyInterceptor` 에 경로 하나를 더하는 일이고,
    M9-3 은 처음부터 이 전제 위에 선다.
- 결과:
  - `AdminKeyInterceptor` 담당: `/content/**` 는 **POST 만** (GET 은 클라 콘텐츠 다운로드라 공개 — M6 C5),
    `/stats/**` 는 **전 메서드**.
  - 완료 기준 추가: `X-Admin-Key` 없이 `GET /stats/events` → 401 (M6.md §7).
  - M9-3 관리자 화면은 이 키를 그대로 쓴다.

## D-014. M0~M5 가 미뤄 둔 잔여 결정 넷 — 일괄 마감 (M6-9)

- 상태: **결정됨** (2026-08-30, Claude 결정 — 아미야 위임. 이견 있으면 되돌림)
- 배경: M0 가 "M6 에서 다시 본다"로 미뤄 둔 항목들 + M3 점검이 넘긴 Content-Type 항목.
  하나씩 절을 세울 무게가 아니라 표로 마감한다 — 미뤄 둔 것을 **잊지 않고 닫았다**는 기록이 목적이다.

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | `spring.profiles.active=local` 이 커밋 파일에 있음 (ANALYSIS §3.6) | **유지** | 배포 환경이 없다. 생기는 날 실행 인자로 옮긴다 — 조건과 함께 주석으로 남아 있어 잊히지 않는다 |
| 2 | `contextLoads` 의 프로필 (ANALYSIS §3.7) | **`@ActiveProfiles("test")` 로 고정** | 프로필 없이 두면 위 #1 때문에 이 "테스트"가 **개발 DB(game)** 에 붙는다. M6 부터는 Flyway 까지 물려 있어 컨텍스트만 띄워도 개발 DB 에 마이그레이션이 도는 셈이었다 — 테스트는 전부 game_test 만 본다 (D-002) |
| 3 | Validation 스타터 (D-005) | **끝내 넣지 않는다** | 수동 if 가 M0~M6 전 구간에서 충분했고, 에러 메시지가 D-004 형식과 이미 정합이다. Bean Validation 은 M9 JPA 과제에서 엔티티와 함께 만나는 편이 학습 순서에 맞다 |
| 4 | Location 절대 URI (M0) + 응답 Content-Type 편차 (M3 점검) | **상대 URI 유지, Content-Type 그대로** | 절대 URI 는 리버스 프록시 뒤에서 호스트를 짐작해야 한다 — 상대가 더 안전하다. Content-Type 편차(콘텐츠 배포만 charset 붙음)는 무해가 확인됐고(M3 §7), 고치면 원본 바이트 배포의 의도가 흐려진다. **"의도한 차이"로 결정하고 기록한다** — M6 계획서 §3-5 가 요구한 "한 번은 결정하고 넘어간다"의 답 |

## D-015. 클라의 chapterVersion 출처 = 에셋 checksum 대조 (M7-0)

- 상태: **결정됨** (2026-08-31, 아미야)
- 배경: M7 착수 현황 재확인(M7.md §2-2)에서 발견 — 클라의 콘텐츠는 에셋 참조(TextAsset)이고
  버전은 서버가 수입 시 붙이는 값이라, 세이브 PUT 의 필수값 `chapterVersion` 을 클라가 알 방법이 없었다.
- 선택지: (1) **에셋 바이트의 SHA-256 을 `GET /content/chapters/{id}/versions` 의 checksum 과 대조해
  자기 버전을 찾는다** (2) latestVersion 을 그대로 가정 (3) 인스펙터에 수동 기입.
- 결정: **1.**
- 판단 근거:
  - checksum 은 **업로드 원본 바이트** 기준이다 (D-006). 클라 에셋이 곧 그 원본 파일이므로
    같은 바이트 → 같은 해시가 성립한다. D-006 이 "클라 무결성 검증에 못 쓴다"고 한 것은
    **다운로드본**(MySQL JSON 정규화로 바이트가 바뀐 것)을 재해싱하는 경우고, 이것과 다르다.
  - 2 는 에셋이 구버전일 때 세이브가 엉뚱한 버전을 가리킨다 — "조용히 틀리는" 종류라 배제.
    3 은 사람이 옮겨 적는 값의 부활(M3-check 가 없앤 것)이라 배제.
  - 대조 실패(서버에 그 checksum 없음)는 **에셋과 서버가 어긋났다는 정직한 신호**다 — 조용히
    넘어가지 않고 드러난다. 이 성질이 1 을 고른 핵심 이유다.
- 결과:
  - Unity 에 서버 `common/Checksum` 과 같은 알고리즘(SHA-256, 소문자 hex)이 하나 필요하다.
  - 시작(또는 회차 생성) 시 1회: checksum 계산 → `GET /content/chapters/{chapterId}/versions` →
    일치하는 `version` 을 보관. 실패 시 세이브 동기화를 막고 사용자에게 알린다(로컬 저장은 계속).
  - `chapterId` 는 에셋 JSON 자체에 있으므로(로더가 이미 읽는다) 추가 입력이 없다.

---

## D-016. 클라 계정 = 게스트 자동 생성, 토큰·자격은 파일 보관 (M7)

- 상태: **결정됨** (2026-08-31, 아미야)
- 배경: M6 부터 세이브 경로가 전부 Bearer 토큰을 요구하는데, 클라에는 로그인 UI 가 없다.
  M7 이 동기화를 세우려면 "누구로 로그인하나"와 "토큰을 어디 두나"를 정해야 했다.
- 선택지: (1) **첫 동기화 때 `guest-{12hex}` 계정을 자동 생성하고, 자격·토큰을
  `persistentDataPath/account.json` 에 보관** (2) PlayerPrefs 보관 (3) 로그인 UI 를 먼저 만든다.
- 결정: **1.**
- 판단 근거:
  - 게스트 계정은 **이 설치가 곧 신원**이다 — 비밀번호도 설치 시 생성한 난수(32hex)라
    파일에 있는 것이 모순이 아니고, 파일을 잃으면 계정을 잃는 것이 게스트의 계약이다.
  - 파일이면 저장 층의 원자적 쓰기(AtomicFile)를 그대로 타고, 사람이 열어 볼 수 있다
    (PlayerPrefs 는 레지스트리에 숨는다). 이 레포의 PlayerPrefs 용례는 오디오 볼륨뿐이다.
  - 3 은 M7 의 목표(저장 포트)가 아니다 — UI 는 뒤 M 이고, 그때 게스트 → 정식 계정
    승격을 붙이면 된다(비밀번호 파일 보관도 그때 토큰만 남기는 것으로 끝난다).
- 결과:
  - `GuestSession` 하나가 문이다: 유효 토큰 → 그대로 / 만료 임박(5분 여유) → 재로그인 /
    계정 없음 → 가입부터 / 서버 안 닿음 → null(동기화만 접고 게임은 계속).
  - 401 을 받은 호출자는 `InvalidateToken()` 후 1회 재시도 — 서버 재시작으로
    sessions 가 빈 경우(개발 중 실제로 일어난다)를 흡수한다.
  - 오프라인 첫 실행이어도 게임은 돈다 — 계정은 서버가 처음 닿는 순간 생긴다.

---

## D-017. 재개는 에피소드 단위 — 선택·스탯 보존, 장면 중간 복원은 뒤 M (M7)

- 상태: **결정됨** (2026-08-31, 아미야)
- 배경: 로컬 세이브에서 "이어하기"를 할 때 어디까지 되돌리는가. 스냅샷에 무엇을 담을지가
  이 결정에 달려 있었다 (M7.md §3-5 — 스냅샷 내용은 클라 결정).
- 선택지: (1) **에피소드 단위** — 저장된 `{chapterId, currentEpisodeId, stats}` 로
  `ProgressionState` 를 복원하고 그 에피소드의 대사를 처음부터 (2) 장면(라인) 단위 —
  nodeName/lineId/Yarn 변수 3종/StageState 까지 복원.
- 결정: **1.** 2 는 뒤 M 으로.
- 판단 근거:
  - 진행의 진실은 [2] 계층(`ProgressionState`)뿐이고, 그것은 세 값으로 완전하다.
    2 가 요구하는 [3] 연출 상태 복원은 **롤백과 같은 문제**(결정론적 리플레이 —
    YarnVariableCheckpoint)라 얇게 만들 수 없다 — M7 의 폭을 넘는다.
  - VN 에서 "보던 에피소드를 처음부터"는 수용 가능한 UX 다 — 대사는 스킵이 있다.
  - 스냅샷 형식은 서버가 열지 않으므로(PLAN 1.4) 뒤 M 에서 [3] 재료를 **더해도**
    서버 변경이 없다 — 지금 좁게 시작해도 되돌릴 것이 없다.
- 결과:
  - 스냅샷(=`LocalSaveFile`) = `{slotNo, chapterId, currentEpisodeId, stats, playSeconds, savedAtUtc}`.
  - 코어에 `ProgressionState.Restore(chapter, episodeId, savedStats)` — 챕터 정의에서
    출발해 저장값을 덮는다(저장 후 스탯이 추가·삭제·범위 변경돼도 성립).
  - 재개 유효성(챕터·에피소드가 아직 있는가)은 드라이버가 판정하고, 깨졌으면
    경고 후 새 게임 — 조용히 이어 가는 척하지 않는다.
- **개정 (2026-09-02, D-018)**: 재개 단위가 에피소드에서 **장면 루트**로 올라갔다. "장면 중간 복원은 뒤 M"이
  그 뒤 M 에서 [3] 스냅샷과 장면 기록으로 해소됐고, 이 항목의 근거 셋 중 "스냅샷을 더해도 서버 변경이 없다"가
  정확히 그대로 성립했다.

---

## D-018. 클라 저장 모델 개편을 서버의 전제로 받는다 — 장면 단위 커밋, 회차 갈래, 즐겨찾기 (M8 착수)

- 상태: **결정됨** (2026-09-02, Unity 핸드오프 `handoff/unity-2026-09-02.md` 기준, 실물 대조 완료)
- 배경: M7 검증 뒤 Unity 레포의 저장 모델이 굳었다. 재개 단위 = 장면 루트, 커밋 = 장면 끝에 한 번(fold),
  롤백 = 장면 안 어디로든(pending 자르기), "확정된 것은 되돌리지 않고 **갈라진다**"(fork), 세이브 슬롯 대신
  **즐겨찾기**(이력 위의 점, 사본). 서버를 향한 코드(`ServerDtos`·`ServerApi`·`ServerSyncSaveStore`)는 바뀌지
  않았다 — 핸드오프의 "M2~M6 그대로"를 실물로 확인했다.
- 결정: **핸드오프 §1~§3 을 그대로 전제로 받는다.** 서버가 새로 배우는 것은 셋뿐이다 — (1) PUT 한 번에 choices
  가 그 장면의 경로 전부(seq 는 여전히 연속), (2) 회차가 여러 줄(갈래)이 되고 로컬 guid 를 가진다,
  (3) 즐겨찾기라는 두 번째 스냅샷이 유저에 매달려 온다. 원칙 "서버는 스냅샷을 열지 않고 기록한다"는 그대로다.
- 판단 근거:
  - 서버가 이미 가진 것이 개편을 그대로 받는다: `(save_slot_id, seq)` UNIQUE 와 replayed 판정은 배치가 커져도
    같은 규칙이고, `(playthrough_id, event_key)` UNIQUE(D-011)는 장면 끝에 몰려 와도 같은 흡수다. **M3 에서
    "클라의 큐 모양이 API 를 정했다"고 했던 것이 여기서 또 맞았다** — 큐가 장면 단위로 커졌을 뿐 모양은 같다.
  - 갈래는 서버에 "되감기 표식"을 요구하지 않는다(핸드오프 §1.3). 옛 회차는 그대로 남고 새 회차가 seq 1 부터
    선다 — 회차마다 선형이라는 M3·M5 의 전제가 유지된다. superseded 제외 로직이 필요 없다는 것이 이 모델의
    가장 큰 서버 쪽 이득이다.
- 결과: 아래 D-019~D-023 이 핸드오프 §4 R1~R7·§5 D-a~D-h 에 대한 서버의 답이다. M8 의 정의가 바뀐다(D-024).

---

## D-019. 회차 생성은 멱등 — 클라 id 로 (핸드오프 R1)

- 상태: **결정됨** (2026-09-02)
- 배경: 갈라지기가 회차 생성을 잦게 만들고, 오프라인 뒤 재시도가 서버 회차를 둘 만들면 이력이 갈라진 채 남는다.
  M7 검증 때 이미 회차 22·26 이 "새 게임" 두 번으로 생겼다 — 지금 POST 는 부르는 만큼 만든다.
- 결정: `POST /users/{uid}/playthroughs { clientPlaythroughId, forkedFrom? }`.
  - `playthroughs.client_id VARCHAR(32) NULL` + `UNIQUE (user_id, client_id)`. 같은 키면 **기존 row 를 200 으로**,
    새로 만들면 201 — M1 의 "같은 파일 재수입은 200"(D-007)과 같은 어법이다.
  - `clientPlaythroughId` 는 **필수**(없으면 400). 선택으로 두면 "안 보낸 요청은 멱등이 아니다"가 되어 M4 의
    optional baseRevision 이 그랬을 것과 같은 있으나 마나가 된다. 지금 도는 클라(F6 전)는 이 400 을 맞는다 —
    **의도한 호환 단절**이고(M6 과 같은 종류), F6 이 곧바로 채운다.
  - 컬럼이 NULL 허용인 이유는 seed 와 M0~M7 의 기존 회차가 id 없이 남기 때문이다. UNIQUE 는 NULL 을 여럿 허용한다.
- 판단 근거: 멱등 키는 요청 UUID 가 아니라 **자원의 클라 측 신원**이어야 한다(D-010 이 choices 를 키로 고른
  것과 같은 이유) — 회차의 신원은 로컬 guid 이고 그것이 파일 이름이다. 32 hex(`Guid.ToString("N")`)라 길이가 고정이다.
- 결과: `PlaythroughCreateRequest` 신설, `PlaythroughService.create` 가 조회 → 없으면 삽입. 응답 모양은
  `{playthroughId}` 그대로(클라의 `Ok` 판정이 2xx 라 200/201 을 구분할 필요가 없다).

---

## D-020. 갈래는 클라 id 로 기록하고 서버 id 는 나중에 잇는다 (핸드오프 R1·R2·D-b·D-d)

- 상태: **결정됨** (2026-09-02)
- 배경: 갈라진 회차의 부모가 서버에 **아직 없을 수 있다**(오프라인에서 새 게임 → 갈라지기 → 온라인). 클라는
  순서를 보장할 수 없고, 서버가 "모르는 부모"를 거부하면 그 갈래는 영영 못 올라간다(D-b).
- 결정:
  - `forked_from_client_id VARCHAR(32) NULL` 은 **항상** 기록한다(갈래면 반드시 온다).
    `forked_from_id BIGINT NULL`(FK self)은 그 순간 부모가 있으면 채우고, 없으면 비워 둔다.
    `forked_scene_index INT NULL` 도 그대로 적는다.
  - **자식 되채우기**: 회차를 새로 만들 때 `UPDATE playthroughs SET forked_from_id = :new WHERE user_id = :uid
    AND forked_from_client_id = :clientId AND forked_from_id IS NULL` 한 문장을 같은 트랜잭션에 둔다. 도착
    순서가 어떻든 그래프는 스스로 닫힌다 — 조회 시 조인으로 때우는 것보다 이쪽이 낫다(읽기가 단순해진다).
  - 갈라진 회차의 `choice_history` 는 seq 1 부터, 부모 것을 복사하지 않는다(R2) — 변화 없음.
  - **통계의 "뿌리" 판정은 `forked_from_client_id IS NULL`** 이다. 핸드오프 D-d 의 `forked_from_id IS NULL` 은
    부모가 아직 안 온 갈래를 뿌리로 잘못 센다. M5 의 지표는 갈래(줄)를 단위로 그대로 두고, "뿌리 수"가 필요한
    자리(user_summary)에만 `forks` 를 하나 더 낸다.
- 결과: V6 마이그레이션, `PlaythroughRepository` 에 findByClientId·insert(확장)·backfillChildren,
  `PlaythroughSummary` 에 `clientPlaythroughId`·`forkedFrom{playthroughId, clientPlaythroughId, sceneIndex}`.

---

## D-021. 즐겨찾기는 유저 소유의 불투명 스냅샷 — 멱등 upsert, revision 없음, soft delete (핸드오프 R3·D-a·D-g·D-h)

- 상태: **결정됨** (2026-09-02)
- 배경: 세이브 슬롯의 자리를 즐겨찾기가 대신한다. 스스로 완결된 사본이라 출처 회차가 없어도 산다.
- 결정:
  - 테이블 `bookmarks(id, user_id FK, client_id VARCHAR(32), chapter_content_id FK, playthrough_id NULL FK,
    playthrough_client_id VARCHAR(32) NULL, scene_index INT, label VARCHAR(100), preview VARCHAR(200),
    snapshot JSON, created_at, updated_at, deleted_at NULL)` + `UNIQUE (user_id, client_id)`. **유저에 매단다**(D-a).
  - `PUT /users/{uid}/bookmarks/{clientBookmarkId}` = 멱등 upsert(201 신규 / 200 갱신), 본문
    `{label, preview, chapterId, chapterVersion, playthroughClientId?, sceneIndex, createdAt, snapshot}`.
    `GET /users/{uid}/bookmarks` 는 **메타만**(스냅샷 없음 — save_slots 목록과 같은 결, F22). `DELETE` 는
    soft(`deleted_at`) — 목록에서 빠지고 row 는 남는다(D-g). 같은 id 로 다시 PUT 하면 되살아난다.
  - **revision·409 가 없다.** 낙관적 동시성은 누적되는 상태(seq 이력 + 스냅샷 계보)를 지키는 장치다.
    즐겨찾기는 누적이 없는 사본이라 지킬 것이 label·preview 뿐이고, 그건 마지막 쓰기가 이기면 된다.
  - `(chapterId, chapterVersion)` → `chapter_content_id` 는 세이브와 같은 404 규칙. 서버는 그 이상 판단하지
    않는다 — 버전이 다른 즐겨찾기를 어떻게 로드할지는 클라의 일(D-h, D-017 의 결).
  - `playthrough_id` 는 D-020 과 같은 방식 — client id 를 항상 적고 서버 id 는 해석되면 채운다.
  - `/users/{uid}/…` 경로라 `AuthInterceptor.USERS_PATH` 의 본인 확인이 **그대로 적용된다** — 새 규칙이 필요 없다.
- 결과: `bookmark/` 패키지 신설(Repository·Service·Controller·DTO 셋), DbCleaner·seed.sql 에 한 줄씩.

---

## D-022. 스냅샷·즐겨찾기 크기 상한 1MB → 413 (핸드오프 D-f)

- 상태: **결정됨** (2026-09-02)
- 배경: 백로그 300줄·장면 기록이 실리면서 스냅샷이 30~60KB 로 커졌다. JSON 컬럼은 여유 있지만 무한은 곤란하다.
- 결정: 서비스 층에서 `writeValueAsString(snapshot)` 의 UTF-8 바이트가 **1,048,576 을 넘으면**
  `PayloadTooLargeException` → 413 `PAYLOAD_TOO_LARGE`. 세이브 PUT 과 즐겨찾기 PUT 둘 다. 전송 층(Tomcat)이
  아니라 서비스에서 재는 이유: Tomcat 은 JSON 본문에 상한을 두는 스위치가 없고, 스냅샷만 재는 것이 뜻에 맞다
  (choices 배열이 큰 것은 다른 문제다). 클라는 백로그 300줄 상한으로 그 아래에 머문다.
- 결과: 예외 클래스 하나, 핸들러 한 줄, 상수 하나(`SaveSlotService`·`BookmarkService` 공유), 테스트 둘.

---

## D-023. [제안 — 아미야 결정 대기] 409 의 기본 해소 = 갈라지기

- 상태: **제안** (2026-09-02). Unity M8 Phase B 에서 결정.
- 배경: M4·M8 계획의 409 해소는 "서버 것(폐기) vs 내 것(force)" 둘이었다. 둘 다 **한쪽의 이력을 버린다.**
  새 모델은 "확정된 것은 되돌리지 않고 갈라진다"고 말한다 — 같은 회차를 두 기기가 다르게 이어 간 것이 곧 갈래다.
- 제안: 409 `CONFLICT` 를 받은 클라는 **로컬 줄을 새 회차로 갈라**(`forkedFrom = {부모 client id, 마지막으로
  서버와 같았던 장면}`) 그 회차로 미전송을 seq 1 부터 다시 올린다. 서버 회차는 그대로, 새 회차가 하나 생긴다.
  아무것도 안 버린다. force 는 남겨 두되 기본 경로가 아니게 된다.
- 서버 쪽 비용: **없다.** D-019·D-020 이 이미 그 경로를 받는다. 결정은 UI 의 것이라 Phase B 로 넘긴다.

---

## D-024. M8 을 두 단계로 다시 정의한다 — A: 서버 계약 확장, B: Unity F6 + 복구·충돌

- 상태: **결정됨** (2026-09-02)
- 배경: PLAN §4 M8 은 "Unity — 복구와 충돌 UI"였고 서버는 문서만 손대는 M 이었다. 핸드오프가 서버 작업
  다섯(R1·R3·R4·D-f·D-d)을 요구하고, 그것이 Unity F6 의 선행이다.
- 결정: **M8-A(서버, 이 레포)**: V6 마이그레이션·멱등 회차·갈래·즐겨찾기·목록 확장·413·통계 뿌리 판정 → 테스트
  → `M8-check.md` 로 PowerShell 검증. **M8-B(Unity)**: F6 다섯 항목 + 복구·충돌 UI(D-023 포함). 완료 기준은
  기존 "두 기기 시나리오"에 "갈라지기·즐겨찾기 왕복"이 더해진다. **PLAN.md 는 정본으로 유지**하고 해석은 이 항목이
  맡는다(D-012 와 같은 어법). M9 의 선택 과제는 그대로다.

---

## 열린 결정 (PLAN §5에서 아직 안 정한 것)

| PLAN# | 결정 | 기본 권장 | 결정 시점 | 상태 |
|---|---|---|---|---|
| 1 | `body` 컬럼 JSON vs LONGTEXT | JSON, diff는 의미 비교 | M1 착수 | **D-006으로 해소** |
| 2 | 슬롯 개수 제한 | 3 | M2 착수 | **D-008로 해소** (권장과 다른 결론) |
| 3 | 요청 UUID 멱등 키 | choices 기반 시작 | M4 착수 | **D-010으로 해소** (권장과 같되, 판정 불가 시 200이 아니라 409) |
| 4 | `event_log` UNIQUE 범위 | (a) 회차 내 1회 | M6 | **D-011로 해소** (권장과 같음) |
| 5 | Flyway | M6 도입 | M6 | **D-012로 해소** (도입 + `game` 재생성) |
| 6 | Testcontainers | — | M0 | **D-002로 해소** |
| — | 영속 시각의 시간대 (PLAN에 없던 항목, ANALYSIS §3.8에서 제기) | UTC | M3 착수 | **D-009로 해소** |

---

## 결정 결과 확인 (2026-08-29, M0 종료 시점)

결정은 내리는 것으로 끝나지 않는다. 대가를 치러 보고 맞았는지 적어 둔다.

| # | 결과 |
|---|---|
| D-001 | **맞았다.** 첫 빌드 컴파일 오류 0, 기존 실습1·2 코드 수정 불필요, `JdbcClient` 사용 가능. 3.x 강의 자료와의 차이는 스타터 이름 두 개뿐이었고 그마저 build.gradle 주석으로 흡수됐다 |
| D-002 | **맞았다.** `game_test`로 테스트가 개발 데이터를 건드리지 않았고, MySQL 고유 동작(UNIQUE 1062, 길이 1406)을 실제 엔진에서 확인했다. 단 **비용도 확인**: `schema.sql`을 손으로 두 번 적용해야 했다 → M6 Flyway의 근거(M6.md §2) |
| D-003 | **작동한다.** 32개 파일이 로컬 폴더에 반영됐고 첫 빌드가 통과했다. 다만 코드량이 늘면 무컴파일 작성의 위험이 커진다 → ANALYSIS R6 |
| D-004 | **맞았다.** 400이 `BAD_REQUEST`(앱)와 `CONSTRAINT_VIOLATION`(DB)으로 갈려서 어느 층이 막았는지 응답만으로 구분됐다. M6의 "에러 형식 통일"은 누락 케이스 채우기만 남는다 |
| D-005 | **맞았다.** 31자 username이 DB에서 거부되는 것을 테스트로 확인 — `sql_mode`의 `STRICT_TRANS_TABLES`가 켜져 있음이 부수적으로 밝혀졌다(M0.md F5). Validation 스타터 도입은 여전히 M6로 미룬다 |

### 2026-08-29, M1 종료 시점

| # | 결과 |
|---|---|
| D-006 | **맞았다.** 5,686 → 3,581 바이트(37% 감소)로 바이트 동일은 깨졌지만, 파싱 후 트리 비교는 완전히 일치했고 한글도 살았다. 그리고 `JSON_EXTRACT`로 `ChoiceLabel`을 바로 뽑을 수 있다 — `LONGTEXT`였다면 매번 `CAST(body AS JSON)`이 필요했다. **줄어든 3,581은 완전 압축(3,240)이 아니다**: MySQL은 `", "` / `": "` 형식으로 출력하므로 사라진 것은 들여쓰기·줄바꿈뿐이다 |
| D-007 | **맞았다.** definition 재수입이 200으로 판정됐다 — checksum 컬럼이 없었다면 불가능했다. 그리고 이 마이그레이션을 두 DB에 손으로 적용하는 과정에서 R4(스키마 드리프트)가 드러나, 결과적으로 M6 Flyway의 근거를 하나 더 만들었다 |

### 2026-08-29, M4 종료 시점

| # | 결과 |
|---|---|
| D-010 | **맞았다. 그리고 구현이 결정보다 단순해졌다.** "판정 불가면 409" 는 코드에 **별도 분기를 만들지 않는다** — `choices` 가 비면 재전송 판정을 건너뛰고 조건부 UPDATE 로 넘어가는데, base 가 낡았으면 0행이 되어 자연히 409 가 난다. 반대로 PLAN 원문(200)을 구현하려면 `if (choices.isEmpty()) return 200` 이라는 **예외 분기를 넣어야** 했다. 실행 검증 §4.7 에서 확인: 낡은 base + `choices` 없음 → 409, base 를 고치면 200. **더 안전한 쪽이 더 단순하기도 한 경우**였다 — 늘 그렇지는 않으므로 기록해 둔다.<br>부수적으로 재검토 방아쇠가 하나 늘었다(F33): force 뒤의 이력은 두 기기가 섞이고, seq 가 겹치면 먼저 쓴 쪽이 남는다. 기기별 UUID 로 바꿀 근거가 "빈 choices" 하나에서 둘이 됐다. |

### 2026-08-29, M3 종료 시점

| # | 결과 |
|---|---|
| D-009 | **맞았다. 그리고 하마터면 절반만 맞을 뻔했다.** `+09:00`으로 보낸 `20:40:19`가 DB에 `11:40:19`로, `Z`로 보낸 값은 그대로 저장됐다(F28 — 이중 변환 없음). 더 중요한 것은 `updated_at`이다: DB의 `CURRENT_TIMESTAMP`가 채우는 이 컬럼이 한국 시각 21:53에 `12:53:25Z`로 찍혔다(F27). **`forceConnectionTimeZoneToSession=true`가 절반을 담당한다** — 권장안대로 `connectionTimeZone=UTC`만 넣었다면 앱이 넣는 `chosen_at`은 UTC, DB가 넣는 `created_at`·`received_at`은 KST로 **한 테이블 안에서 갈렸을 것**이고, 둘 다 그럴듯한 시각이라 눈으로는 못 잡았을 것이다. 구현 전 Connector/J 문서를 읽지 않았다면 이 플래그의 존재 자체를 몰랐다 (ANALYSIS §4.3) |

### 2026-08-29, M2 종료 시점

| # | 결과 |
|---|---|
| D-008 | **맞았다.** 슬롯 0·128은 `400 {"code":"BAD_REQUEST","message":"슬롯 번호는 1~127 범위여야 합니다: 0"}`, 1·2·3·5·42·127은 전부 성공해 목록에 6개가 나왔다. PLAN 권장(3개 제한)을 따랐다면 5·42·127이 막혔을 것이고, 그건 **데이터 무결성이 아니라 게임 디자인을 서버가 정한 것**이 된다. 대체 기준("0·128 → 400" + "상한 없음의 증명")이 원래 기준("슬롯 4 → 400")보다 실제로 무엇을 보장하는지 더 정확히 말한다 |
