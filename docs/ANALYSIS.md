# 레포·PLAN.md 분석 — 코드를 쓰기 전에 확인한 것

> 작성: 2026-08-28 (M0 착수 직전). 이 문서는 "계획을 그대로 믿고 시작하면 어디서 깨지는가"를 미리 찾은 기록이다.
> 갱신 규칙: 사실이 바뀌면(버전 업, 결정 변경) 해당 절만 고치고 맨 아래 변경 이력에 한 줄 남긴다.

---

## 0. 숙련자는 기존 계획을 이렇게 받는다

계획서(PLAN.md)가 잘 쓰여 있어도 **계획이 세워진 시점의 전제**와 **지금 레포의 실제 상태**는 어긋나 있기 마련이다.
그래서 첫 작업은 코드 작성이 아니라 다음 세 가지를 대조하는 것이다.

| 대조 대상 | 방법 | 이 레포에서 나온 결과 |
|---|---|---|
| 계획의 환경 전제 vs 실제 빌드 파일 | `build.gradle`, `gradle-wrapper.properties`, IDE 설정을 직접 연다 | **Boot 버전 불일치** (§3.1) — 가장 큰 발견 |
| 계획이 쓰는 API가 그 버전에 실재하는가 | 프레임워크 소스/릴리스 노트로 확인 | `JdbcClient`는 Boot 3.1.0에 없다 (§3.2) |
| 계획의 "함정"이 실제 DB에서 그대로 재현되는가 | DDL과 DB 엔진 동작을 대조 | `TRUNCATE`는 FK 부모 테이블에서 실패한다 (§3.5) |

원칙: **계획을 의심하되, 계획을 함부로 바꾸지 않는다.** 어긋난 점은 기록하고 선택지를 만들어 결정권자(아미야님)에게 묻는다. 결정된 것은 `DECISIONS.md`에 남겨 나중에 "왜 이렇게 했지?"를 다시 묻지 않게 한다.

---

## 1. 레포 현재 상태 (브랜치 `실습3_TextRPGDB연동`, HEAD `8b1f394`)

### 1.1 커밋 흐름

```
c68444c init                              ← Initializr 산출물: Boot 4.1.1, Gradle 9.5.1, Java 17 toolchain
b497727 스프링 부트 서버 실행               ← Boot 4.1.1 → 3.1.0, starter-webmvc → starter-web 로 변경
… (실습1: TestController, BookController)
524df0f Import Thymeleaf                   ← 실습2 시작, starter-webmvc-test → starter-test
… (실습2: MemoController CRUD, static index.html)
2e82e58 MySQL JDBC 의존성, 데이터 설정 추가    ← spring-boot-starter-jdbc, mysql-connector-j 추가
daded99 DB 접속 정보 파일 gitignore 처리
8b1f394 docs: add TextRPG integration plan and database schema DDL   ← PLAN.md, schema.sql
```

`실습2_CRUD` 브랜치와 `dev`는 `daded99`에서 멈춰 있고, 이 브랜치만 `docs/`가 있다.

### 1.2 코드 인벤토리

| 파일 | 역할 | 실습3와의 관계 |
|---|---|---|
| `SpringPrepareApplication` | 진입점 | 그대로 |
| `controller/TestController` | 실습1 (PathVariable, RequestParam, Body) | 손대지 않음 |
| `controller/BookController`, `entity/Book`, `dto/Book*Dto` | 실습1 (Map 저장) | 손대지 않음 |
| `controller/MemoController`, `entity/Memo`, `dto/Memo*Dto` | 실습2 (Map CRUD, Lombok) | 손대지 않음. 존재하지 않는 id에 `IllegalArgumentException` → 현재 500 |
| `static/index.html`, `images/*` | 실습2 메모장 화면 | 그대로 |
| `application.properties` | `driver-class-name`, `spring.profiles.active=local` | 유지 (§3.6) |
| `application-local.properties` (gitignore) | `url=jdbc:mysql://localhost:3306/game?serverTimezone=Asia/Seoul`, username, password | 로컬 PC에 존재 확인 |
| `docs/PLAN.md`, `docs/schema.sql` | 실습3 계획·DDL | 이 문서의 대조 대상 |
| `SpringPrepareApplicationTests.contextLoads` | 기본 테스트 | `@ActiveProfiles` 없음 → local 프로필로 뜸 (§3.7) |

기존 실습1·2 코드는 **클래스 + Lombok** 스타일, PLAN §2.5는 **record** 스타일이다. 두 스타일이 한 레포에 공존하게 되는데, 이는 의도된 것이다 — 실습3의 새 패키지(`common/`, `user/`, …)만 새 규약을 따르고, 기존 패키지는 건드리지 않는다.

### 1.3 로컬 PC 환경 (확인된 사실)

- Windows, IntelliJ. `.idea/gradle.xml`: Gradle JVM = `ms-21` (Microsoft JDK 21), 프로젝트 언어 레벨 17. `build.gradle`의 toolchain은 17 → Gradle이 실행은 JDK 21로 하고 컴파일은 JDK 17 toolchain을 쓴다 (foojay resolver가 없으면 받아옴).
- `.gradle/9.5.1/` 디렉터리 존재 → Gradle 9.5.1 wrapper가 실제로 이 프로젝트에서 실행된 적이 있다.
- `HELP.md`가 Boot 4.1.1 문서 링크 → Initializr가 4.1.1로 생성한 프로젝트가 맞다.
- MySQL 8.x 로컬, Workbench 사용 (PLAN 전제). `game` DB에 schema.sql 적용 여부는 **M0-1에서 확인**한다.

### 1.4 이 작업 세션의 제약 (Claude 쪽)

- 클라우드 작업 공간은 Maven Central·Gradle 배포 서버 접근이 막혀 있다 → **컴파일·실행·테스트 불가**. 코드는 Spring Boot 4.1.1 / Spring Framework 7.0.8 소스(GitHub raw)를 직접 대조해 작성한다.
- GitHub push 불가 → 파일은 연결된 로컬 클론 폴더에 직접 써넣는다. **빌드·실행·커밋은 아미야님이 IntelliJ에서** 한다.
- 따라서 매 작업의 "완료"는 두 단계다: (1) Claude가 파일을 써넣음, (2) 아미야님이 실행해 완료 기준 통과를 확인. 계획서의 상태 표기도 이 둘을 구분한다.

---

## 2. PLAN.md 요지 (다시 읽지 않아도 되게)

- 서버 = **콘텐츠 창고 + 세이브 금고**. 판정·스탯 계산·해금 규칙은 절대 서버로 가져오지 않는다 ("두 곳에 있으면 갈린다").
- 트랜잭션·멱등성·충돌은 **락이 아니라 데이터(UNIQUE, revision)** 로 푼다. 이것이 M4의 핵심 학습.
- 계층: `Controller → Service(@Transactional) → Repository(JdbcClient, SQL 상수 노출)`. DTO·행 매핑은 record. JSON 컬럼은 String으로 통과, 파싱은 `JsonNode`까지만.
- 마일스톤은 순서대로, 완료 기준 전부 통과 전 다음으로 안 감. `[결정 필요]`는 임의로 정하지 않는다. "하지 않는 것"은 의도적 제외.
- M0 접속 → M1 콘텐츠 수입 → M2 세이브 → M3 이력·이벤트 → M4 멱등·충돌 → M5 집계·EXPLAIN → M6 마감(인증·에러 통일) → M7·M8 Unity → M9 선택.

---

## 3. 불일치·함정 목록 (계획과 현실이 어긋난 지점)

번호는 `DECISIONS.md`의 D-번호와 대응한다.

### 3.1 [D-001] Spring Boot 버전: PLAN 4.1.1 vs build.gradle 3.1.0

- 사실: `b497727`에서 4.1.1 → 3.1.0으로 내려갔고 이후 그대로다. PLAN §2.1·§2.2는 4.1.1과 4.x 스타터 이름(`starter-webmvc`, `starter-webmvc-test`)을 전제한다.
- 영향:
  - 3.1.0에는 `JdbcClient`가 없다 (Spring Framework 6.1 = Boot 3.2에서 도입). PLAN §2.4의 레포지토리 규약이 성립하지 않는다.
  - Boot 3.x 전 라인은 2026-06-30 3.5 EOL로 OSS 지원이 끝났다. 3.1.0은 2023년 5월 릴리스.
  - Gradle 9.5.1 wrapper와 Boot 3.1.0 Gradle 플러그인은 호환이 보장되지 않는다 (Boot 4.1.1은 "Gradle 8.14+ 또는 9.x" 공식 지원).
- 결정: **4.1.1로 복귀** (아미야님 확인, 2026-08-28). 상세는 DECISIONS.md.
- 따라오는 변경: 스타터 이름 원복, Jackson이 **3.x**(`tools.jackson.*` 패키지)로 바뀜 → M1·M2의 `JsonNode`/`ObjectMapper` import가 3.x 강의 자료와 다르다. `@JsonProperty` 등 애노테이션은 `com.fasterxml.jackson.annotation` 그대로.

### 3.2 [D-001 파생] Boot 4.1.1에서 확인한 API·의존성 (소스 대조 완료)

| 항목 | 확인 결과 |
|---|---|
| `spring-boot-starter-webmvc` | starter, starter-jackson, starter-tomcat, http-converter, webmvc 모듈 포함 |
| `spring-boot-starter-jdbc` | HikariCP + `spring-boot-jdbc` 모듈. `JdbcClientAutoConfiguration` 존재 → `JdbcClient` 빈 자동 등록 |
| `spring-boot-starter-webmvc-test` | starter-test(JUnit5, AssertJ, Mockito, json-path, jsonassert) + webmvc + `spring-boot-webmvc-test` + resttestclient |
| `spring-boot-starter-jdbc-test` | `@JdbcTest` 슬라이스용. 지금은 안 씀 (통합 테스트는 `@SpringBootTest`) |
| Lombok | Boot 4.1.1 BOM이 1.18.46 관리 → 버전 생략 가능 (기존 코드 유지용) |
| `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure` 패키지 (3.x와 다름) |
| `@SpringBootTest` | `org.springframework.boot.test.context` (동일) |
| `JdbcClient` | `sql().param().query(Class).optional()/single()/list()`, `update(KeyHolder)` — Framework 7.0.8 소스 확인 |
| `.query(Record.class)` | `SimplePropertyRowMapper` → 생성자 파라미터명으로 매핑, 못 찾으면 `snake_case`로 재시도 → PLAN M0 "함정" 그대로 성립 |
| `KeyHolder.getKey()` | `@Nullable Number` — null 처리 필요 |

### 3.3 [D-002] 통합 테스트 DB

- PLAN §2.6 열린 결정 #6. 결정: **로컬 MySQL `game_test`** (Docker 없음). Testcontainers는 M6 이후 재검토.
- 파생: `src/test/resources/application-test.properties`(gitignore) + `.example` 템플릿. `game_test`에도 schema.sql을 적용해야 한다 (M0-check.md에 절차).

### 3.4 코드 전달 경로

- 로컬 클론 폴더 연결 방식 확정. 세션 종료 후에도 파일은 로컬에 남는다. git 커밋은 아미야님이 한다 (커밋 메시지 형식 `M0: 접속 확인` — PLAN §0).

### 3.5 PLAN §2.6 "테이블 TRUNCATE"는 MySQL에서 그대로 안 된다

- `users`는 `devices`, `playthroughs`가 FK로 참조한다. MySQL은 **자식 테이블이 비어 있어도** FK 부모에 `TRUNCATE`를 거부한다 (`Cannot truncate a table referenced in a foreign key constraint`).
- 우회 1: `SET FOREIGN_KEY_CHECKS=0` — 세션 변수라 커넥션 풀에서 같은 커넥션에서 실행됨을 보장해야 하고, TRUNCATE는 암묵 커밋을 일으킨다. 초심자에게 함정이 많다.
- 우회 2: **자식 → 부모 순서로 `DELETE`**. 느리지만 FK 방향을 눈으로 익히게 되고, 테스트 데이터 규모에선 차이가 없다. → 채택. 테스트 지원 클래스 `DbCleaner`에 순서를 상수로 둔다 (M마다 테이블이 늘면 여기만 갱신).

### 3.6 `spring.profiles.active=local`이 커밋된 `application.properties`에 있다

- 편하지만 "이 레포는 항상 local 프로필로 뜬다"를 코드에 박는 셈이라 CI·배포에서 문제가 된다. 지금은 **유지**한다 (개발자 한 명, 배포 없음). M6 README 작성 시 `--spring.profiles.active=local` 실행 인자 방식으로 옮길지 결정.
- 테스트는 `@ActiveProfiles("test")`로 덮어쓴다 (`@ActiveProfiles`가 `spring.profiles.active`보다 우선).

### 3.7 `contextLoads` 테스트는 DB 접속 정보 없이는 뜨지 않는다

- `spring-boot-starter-jdbc`가 들어간 순간 DataSource 자동 설정이 `spring.datasource.url`을 요구한다. 로컬 프로필 파일이 없는 환경(새 클론, CI)에서는 실패. 지금 PC에는 파일이 있어 통과. M0에서는 손대지 않고, M6에서 테스트 프로필로 통일한다.

### 3.8 시간대: `serverTimezone=Asia/Seoul`과 `DATETIME`

- `created_at DEFAULT CURRENT_TIMESTAMP`는 **MySQL 서버 세션 시각**이다. 드라이버는 URL의 `serverTimezone`으로 DATETIME을 해석한다. M0에서는 "DB 시각이 들어간다"만 확인하면 된다.
- M3에서 클라 시각(`chosenAt`, ISO-8601 UTC)을 `DATETIME`에 넣을 때 **UTC ↔ Seoul 변환이 조용히 일어난다**. M3 계획서 주의사항에 올려 둔다.

### 3.9 문서끼리의 작은 어긋남 (고치지 않고 기록만)

- `schema.sql` 주석은 충돌 해소·멱등성을 "M5"라고 쓰고, PLAN.md는 M4다. PLAN.md가 정본. schema.sql은 DDL 정본이지 마일스톤 정본이 아니다.
- `docs/PLAN.md` §2.1 "Java 17" — toolchain 17, 실행 JDK 21. Boot 4.1.1 최소 요건은 17이므로 문제 없음. record·text block·switch 표현식 등 17 문법은 자유롭게 쓴다.
- PLAN M0 API는 `POST /users` — 기존 실습 코드의 `/api/books`, `/api/memos`와 접두사가 다르다. 의도적으로 PLAN을 따른다 (실습3 API는 `/api` 접두사 없음). M6에서 인터셉터 경로 패턴을 정할 때 다시 본다.

---

## 4. 리스크 등록부

| # | 리스크 | 징후 | 대응 | 상태 |
|---|---|---|---|---|
| R1 | 컴파일 없이 쓴 코드의 오타·시그니처 오류 | 첫 `bootRun`에서 컴파일 에러 | 에러 메시지 전체를 붙여 주면 즉시 수정 | **종결** 2026-08-29 — 첫 빌드 오류 0, 테스트 6/6 |
| R2 | Boot 4.1.1 복귀 시 Gradle 의존성 해석 실패 | `Could not resolve …webmvc` | PLAN §2.2 지시대로 추측 변경 금지 | **종결** — 해석 성공 |
| R3 | 기존 실습1·2 코드가 Boot 4에서 깨짐 | Thymeleaf·Lombok 관련 컴파일 에러 | 깨지면 실습 코드는 최소 수정 | **종결** — 수정 없이 컴파일됨 |
| R4 | `game`과 `game_test` 스키마 드리프트 | 테스트만 실패/성공 | M6 Flyway 결정 시 두 DB 자동 적용. 그 전까지 수동 동기화 | **현실화 시작** — M0에서 이미 수동 2회 적용. M6 근거로 M6.md §2에 기록 |
| R5 | MySQL JSON 컬럼 정규화로 "원본 그대로" 깨짐 | M1 diff ≠ 0 | PLAN 열린 결정 #1. M1 착수 시 결정 | 미해소 — M1 |
| R6 | 컴파일 없이 작성하는 방식이 M이 커질수록 비용이 커짐 | M1 이후 코드량 증가 시 첫 빌드 실패율 상승 | M0는 12파일에 오류 0이었다. 다만 M1은 트랜잭션·배치·JSON이 얽혀 난이도가 다르다. 파일을 나눠 반영하고 중간에 한 번 빌드하는 편이 낫다 | 신규 — M1에서 관찰 |

---

## 5. 변경 이력

- 2026-08-28 최초 작성. D-001(Boot 4.1.1), D-002(game_test), 전달 경로(로컬 폴더) 반영.
- 2026-08-29 M0 검증 후: R1·R2·R3 종결, R4 현실화 기록, R6 신설. §3.1의 Boot 버전 불일치는 해소됨(4.1.1 실동작 확인). §3.5(TRUNCATE)·§3.8(시간대)은 M0에서 실측으로 뒷받침됨 — 각각 `DbCleaner` 동작 확인, `created_at` KST 정상 기록.
