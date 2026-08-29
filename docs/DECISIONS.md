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

---

## 열린 결정 (PLAN §5에서 아직 안 정한 것)

| PLAN# | 결정 | 기본 권장 | 결정 시점 | 상태 |
|---|---|---|---|---|
| 1 | `body` 컬럼 JSON vs LONGTEXT | JSON, diff는 의미 비교 | M1 착수 | **D-006으로 해소** |
| 2 | 슬롯 개수 제한 | 3 | M2 착수 | **D-008로 해소** (권장과 다른 결론) |
| 3 | 요청 UUID 멱등 키 | choices 기반 시작 | M4 착수 | **D-010으로 해소** (권장과 같되, 판정 불가 시 200이 아니라 409) |
| 4 | `event_log` UNIQUE 범위 | (a) 회차 내 1회 | M6 | 미결 |
| 5 | Flyway | M6 도입 | M6 | 미결 |
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

### 2026-08-29, M3 종료 시점

| # | 결과 |
|---|---|
| D-009 | **맞았다. 그리고 하마터면 절반만 맞을 뻔했다.** `+09:00`으로 보낸 `20:40:19`가 DB에 `11:40:19`로, `Z`로 보낸 값은 그대로 저장됐다(F28 — 이중 변환 없음). 더 중요한 것은 `updated_at`이다: DB의 `CURRENT_TIMESTAMP`가 채우는 이 컬럼이 한국 시각 21:53에 `12:53:25Z`로 찍혔다(F27). **`forceConnectionTimeZoneToSession=true`가 절반을 담당한다** — 권장안대로 `connectionTimeZone=UTC`만 넣었다면 앱이 넣는 `chosen_at`은 UTC, DB가 넣는 `created_at`·`received_at`은 KST로 **한 테이블 안에서 갈렸을 것**이고, 둘 다 그럴듯한 시각이라 눈으로는 못 잡았을 것이다. 구현 전 Connector/J 문서를 읽지 않았다면 이 플래그의 존재 자체를 몰랐다 (ANALYSIS §4.3) |

### 2026-08-29, M2 종료 시점

| # | 결과 |
|---|---|
| D-008 | **맞았다.** 슬롯 0·128은 `400 {"code":"BAD_REQUEST","message":"슬롯 번호는 1~127 범위여야 합니다: 0"}`, 1·2·3·5·42·127은 전부 성공해 목록에 6개가 나왔다. PLAN 권장(3개 제한)을 따랐다면 5·42·127이 막혔을 것이고, 그건 **데이터 무결성이 아니라 게임 디자인을 서버가 정한 것**이 된다. 대체 기준("0·128 → 400" + "상한 없음의 증명")이 원래 기준("슬롯 4 → 400")보다 실제로 무엇을 보장하는지 더 정확히 말한다 |
