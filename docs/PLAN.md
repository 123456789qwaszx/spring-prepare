# ked VN 게임 서버 — 작업 계획

Spring Boot + MySQL로 만드는 **콘텐츠 창고 + 세이브 금고**.
`ked-presentation-runtime`(Unity, Yarn Spinner)이 비워둔 [1] 영구 계층과 `ISaveStore` 포트를 채운다.

---

## 0. 이 문서를 읽는 법 (Claude Code용)

- 이 문서는 정본이다. 진행 상태·결정·M별 세부 계획은 별도 문서에 있다: `docs/README.md`(문서 지도) → `STATUS.md`, `DECISIONS.md`, `ANALYSIS.md`, `plans/M{n}.md`, `M{n}-check.md`.
- 마일스톤은 **순서대로** 진행한다. 한 마일스톤의 "완료 기준"을 전부 통과하기 전에 다음으로 넘어가지 않는다.
- 각 마일스톤은 독립적으로 커밋 가능한 단위다. 커밋 메시지는 `M1: 챕터 수입 API` 형식.
- "하지 않는 것" 항목은 **의도적 제외**다. 필요해 보여도 추가하지 않는다. 필요하면 사용자에게 먼저 묻는다.
- 판단이 필요한 지점은 `[결정 필요]`로 표시돼 있다. 임의로 정하지 말고 사용자에게 선택지를 제시한다.
- 사용자는 Java/Spring 초심자다. 코드를 작성할 때 **왜 이렇게 하는지** 한두 줄 주석 또는 설명을 붙인다. 단, 마법(리플렉션 트릭, 과한 추상화)은 쓰지 않는다.

---

## 1. 배경과 확정된 설계

### 1.1 게임

선택지 분기가 많은 비주얼 노벨. 싱글 플레이, 모바일 중심, **오프라인 플레이 가능**.

### 1.2 클라이언트 쪽 3계층 (런타임 코드 기준)

| 계층 | 수명 | 주인 | 내용 |
|---|---|---|---|
| [1] 영구 | 시나리오/회차 | **비어 있음 → 이 서버** | 클리어 이력, EventKey 발생, 엔딩, 해금 |
| [2] 챕터 | 챕터 하나 | `Ked.Progression` (순수 C#) | `ProgressionState` = 현재 에피소드 + 스탯 dict |
| [3] Yarn | 에피소드/노드 | Yarn Spinner | 노드 내 변수, `StageState` (정지 프레임) |

### 1.3 콘텐츠 파이프라인

```
VnTool(저작) → *.progression.json (챕터 단위, 챕터 간 관계 없음)
             → game.definition.json (스탯 카탈로그, EventKey → 해금 규칙)
             → 이 서버가 버전 붙여 보관
             → Unity가 내려받아 재생
```

챕터 간 관계는 VnTool이 다루지 않는다. 에피소드의 `EventKey`가 발생하면 Unity가 그것을 근거로 다음 챕터를 연다. **해금 규칙은 `game.definition.json`에 실리고, 클라와 서버가 같은 파일을 읽는다.** 서버 전용 규칙 테이블은 두지 않는다 (오프라인에서도 열려야 하므로).

### 1.4 서버의 역할 — 확정

| 한다 | 하지 않는다 |
|---|---|
| 콘텐츠를 버전별로 보관·배포 | 조건 평가, 스탯 계산, 선택지 판정 (`ChapterTransition`/`Commit`의 Java 복제) |
| 세이브 스냅샷 저장·복구 | 스냅샷 내부 해석 |
| 선택 이력·EventKey 로그 보관 | 서버 권위 (치트 검증) |
| 재전송 흡수, 두 기기 충돌 감지 | 실시간 개입 (매 선택마다 서버 왕복) |
| 집계 쿼리 | 챕터 해금 규칙 소유 |

**근거**: 싱글 플레이 VN에는 지킬 공유 상태가 없다. 평가기를 세 번째로 복제하면 `Ked.Progression` 원칙("두 곳에 있으면 갈린다")에 어긋나고 얻는 것이 없다. 트랜잭션·멱등성·충돌 처리는 회피 대상이 아니라 이 게임이 실제로 요구하는 문제이며, 락이나 비동기 조율 없이 데이터(UNIQUE, revision)로 푼다.

### 1.5 진행 JSON의 모양 (서버가 읽는 필드만)

```json
{
  "ChapterId": "qwer",
  "DisplayName": "qwer",
  "StartEpisodeId": "EP01",
  "Stats": [ { "Key": "int", "Initial": 0, "Minimum": 0, "Maximum": 5, ... } ],
  "Nodes": [
    {
      "EpisodeId": "EP01",
      "Title": "",
      "DialogueEntryId": "EP01",
      "EventKey": "",
      "NextOptions": [ { "TargetEpisodeId": "EP02_01", "ChoiceLabel": "...", "StatChanges": [...] } ]
    }
  ]
}
```

서버가 색인으로 뽑는 것: `ChapterId`, `DisplayName`, `StartEpisodeId`, 각 `Nodes[]`의 `EpisodeId`, `Title`, `EventKey`, `NextOptions.length`. 나머지는 해석하지 않고 `body`에 원본 그대로 둔다.

---

## 2. 프로젝트 규약

### 2.1 환경

- Spring Boot `4.1.1`, Java 17, Gradle 9.5.1 (wrapper)
- MySQL 8.x 로컬, DB 이름 `game`, 프로필 `local`
- 실행: `./gradlew bootRun` / 테스트: `./gradlew test`

### 2.2 의존성 (`build.gradle`)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
implementation 'org.springframework.boot:spring-boot-starter-jdbc'
runtimeOnly   'com.mysql:mysql-connector-j'
compileOnly   'org.projectlombok:lombok'
annotationProcessor 'org.projectlombok:lombok'
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
```

> Boot 4.x는 스타터 이름이 3.x와 다르다. 의존성이 해석되지 않으면 Boot 4.1 릴리스 노트를 확인하고 사용자에게 알린다. 추측으로 이름을 바꾸지 않는다.

M6 전까지 Security, JPA, Redis, MapStruct 등 추가 의존성을 넣지 않는다.

### 2.3 설정 파일

```
src/main/resources/application.properties          # 공통 (커밋)
src/main/resources/application-local.properties    # DB 접속 (gitignore)
```

`application-local.properties`는 `.gitignore`에 추가돼 있어야 한다. 없으면 추가한다.

### 2.4 패키지 구조

```
com.sparta.springprepare
 ├─ SpringPrepareApplication
 ├─ common/          예외, 에러 응답, 공용 유틸(checksum)
 ├─ user/            M0
 ├─ content/         M1  (chapter, definition)
 ├─ playthrough/     M2
 ├─ save/            M2~M4  (slot, choice, event)
 └─ stats/           M5
```

각 패키지 안: `XxxController` → `XxxService` → `XxxRepository`. 레포지토리는 `JdbcClient`로 SQL을 직접 쓴다. SQL은 문자열 상수로 메서드 바로 위에 둔다 (숨기지 않는다).

### 2.5 코드 규약

- DTO는 `record`. 요청 `XxxRequest`, 응답 `XxxResponse`.
- DB 행 매핑도 `record`. 컬럼 `snake_case` ↔ 필드 `camelCase`는 `JdbcClient`의 자동 매핑에 맡긴다.
- 트랜잭션 경계는 **Service 메서드**에 `@Transactional`. Controller/Repository에는 붙이지 않는다.
- JSON 컬럼은 `String`으로 주고받는다. 서버는 파싱이 필요할 때만 `JsonNode`로 읽고, 절대 도메인 객체로 역직렬화하지 않는다 (해석 금지 원칙).
- 예외 → HTTP 매핑은 `common/GlobalExceptionHandler` 한 곳에서. 각 마일스톤에서 필요한 예외를 여기에 추가한다.

### 2.6 테스트 전략

- 단위 테스트: 순수 로직(checksum, 색인 추출)만.
- 통합 테스트: `@SpringBootTest` + 로컬 MySQL의 별도 DB `game_test`. 각 테스트 전에 관련 테이블 `TRUNCATE`.
- `[결정 필요]` Testcontainers 도입 여부. 사용자가 Docker를 쓰지 않으면 로컬 DB로 간다.
- 매 마일스톤의 완료 기준은 **curl 또는 Postman으로 재현 가능한 시나리오**로 적혀 있다. 자동 테스트와 별개로 그 시나리오를 사용자가 직접 돌려볼 수 있게 `docs/M{n}-check.md`에 curl 명령을 남긴다.

---

## 3. 스키마 (`schema.sql`)

전체 DDL은 `schema.sql` 참조. 요약:

```
users, devices                      계정·기기
game_definitions, chapter_contents  콘텐츠 (버전별 원본 JSON)
chapter_episodes                    콘텐츠 색인 (episode_id, event_key, option_count)
playthroughs                        회차 = [1]의 그릇
save_slots                          [2]+[3] 스냅샷, revision
choice_history                      선택 이력, (save_slot_id, seq) UNIQUE
event_log                           EventKey 발생, [1]의 실체
```

핵심 제약:
- `save_slots.chapter_content_id` → 특정 **버전**을 가리킨다 (chapter_id가 아님).
- `choice_history (save_slot_id, seq)` UNIQUE → 재전송 흡수.
- `save_slots.revision` → 두 기기 충돌 감지.
- `event_log` UNIQUE 범위는 M6에서 확정 `[결정 필요]`.

스키마 변경이 필요하면 `schema.sql`을 직접 고치지 말고 `db/migrations/V{n}__desc.sql`을 추가한다. (Flyway는 M6 이후 도입 검토.)

---

## 4. 마일스톤

### M0. 접속 확인

**목표**: Spring → MySQL 왕복이 되고, DB 제약이 애플리케이션 밖에서 동작한다는 감각.

**작업**
1. `schema.sql` 적용 확인 (`users` 테이블 존재).
2. 의존성·프로필 설정 (§2.2, §2.3).
3. `user/User`, `UserRepository`, `UserController`.
4. `common/GlobalExceptionHandler`: `DuplicateKeyException` → 409.

**API**
```
POST /users            {username, password}  → 201 {id, username}
GET  /users/{id}                              → 200 {id, username} | 404
```

**완료 기준**
- POST → 201, Workbench `SELECT * FROM users`에 행이 보인다.
- 같은 username 재요청 → 409 (500이 아님).
- 없는 id GET → 404.

**하지 않는 것**: 로그인, 비밀번호 해시, 토큰. (M6)

**함정**
- `created_at` DEFAULT를 믿고 INSERT에서 빼야 DB 시각이 들어간다.
- `.query(User.class)`는 컬럼명 `created_at` ↔ `createdAt`를 자동 매핑한다. 다른 이름을 쓰면 null이 조용히 들어온다.

---

### M1. 콘텐츠 수입·배포

**목표**: VnTool 산출물을 버전 붙여 보관하고, 같은 파일 재수입은 멱등하게 처리한다. 첫 트랜잭션.

**작업**
1. `common/Checksum`: SHA-256(hex) of raw bytes.
2. `content/ChapterImportService.importChapter(String rawJson)`:
   - checksum 계산 → `chapter_contents.checksum`에 이미 있으면 **기존 행 반환** (200, 신규 아님).
   - `JsonNode`로 `ChapterId`, `DisplayName`, `StartEpisodeId`, `Nodes[]` 읽기. 없거나 `Nodes`가 비면 400.
   - `version = COALESCE(MAX(version),0)+1 WHERE chapter_id=?`
   - `chapter_contents` INSERT → 생성된 id로 `chapter_episodes` 배치 INSERT (`option_count = NextOptions.length`).
   - 위 전부 `@Transactional`.
3. `content/DefinitionService`: 같은 패턴, 색인 없음.
4. 조회 API.

**API**
```
POST /content/chapters                 body: 원본 JSON      → 201 {chapterId, version, episodeCount} | 200 (이미 있음)
GET  /content/chapters                                      → 200 [{chapterId, latestVersion, displayName}]
GET  /content/chapters/{chapterId}/versions                 → 200 [{version, importedAt, checksum}]
GET  /content/chapters/{chapterId}/{version}                → 200 원본 JSON 그대로 | 404
GET  /content/chapters/{chapterId}/latest                   → 200 원본 JSON

POST /content/definition               body: 원본 JSON      → 201 {version} | 200
GET  /content/definition/latest                             → 200 원본 JSON
GET  /content/definition/{version}                          → 200 | 404
```

**완료 기준**
- `qwer.progression.json` POST → 201, version 1. 다시 POST → 200, version 1 (행 안 늘어남).
- JSON 한 글자 바꿔 POST → 201, version 2.
- `GET .../qwer/2` 응답을 파일로 저장해 원본과 `diff` → 차이 0.
- `chapter_episodes`에 노드 수만큼 행, `event_key`·`option_count` 정확.
- `chapter_episodes` INSERT 도중 예외를 일부러 던지면 `chapter_contents`도 남지 않는다 (롤백 확인).

**하지 않는 것**
- 조건·스탯·옵션 테이블 정규화.
- JSON 스키마 검증 (필수 키 존재 여부 이상). 구조 검증은 VnTool과 런타임 로더의 일.
- 인증. 지금은 누구나 올릴 수 있다. (M6)

**함정**
- MySQL JSON 컬럼은 저장 시 키 순서·공백을 정규화한다. `GET`에서 원본과 바이트 단위로 같아야 한다면 `body`를 `LONGTEXT`로 바꿔야 한다. **`[결정 필요]`** — 기본 권장: JSON 유지, "diff 0"은 파싱 후 의미 비교로 확인.
- version 계산과 INSERT 사이의 경쟁은 무시한다 (수입은 개발자 한 명이 수동으로 하는 작업).
- `checksum` UNIQUE와 "이미 있음 → 200" 로직이 둘 다 있다. UNIQUE는 안전망, 로직은 정상 경로.

---

### M2. 회차·세이브 업로드/복구

**목표**: 클라 로컬 세이브를 서버에 올리고 새 기기에서 내려받는다. 외래키가 잘못된 세이브를 막는다.

**작업**
1. `playthrough/`: 생성·목록·종료.
2. `save/SaveSlotService.upsert(...)`:
   - `chapterId + chapterVersion` → `chapter_contents.id` 조회. 없으면 404.
   - `deviceKey` → `devices` upsert (`user_id, device_key` UNIQUE), `last_seen_at` 갱신.
   - `save_slots` upsert: `INSERT ... ON DUPLICATE KEY UPDATE ..., revision = revision + 1`.
   - 응답에 서버 `revision` 포함.
3. 조회: 회차의 슬롯 목록 (스냅샷 제외 요약) / 슬롯 하나 (스냅샷 포함).

**API**
```
POST /users/{userId}/playthroughs                        → 201 {playthroughId}
GET  /users/{userId}/playthroughs                        → 200 [{id, startedAt, endedAt, slotCount}]
POST /playthroughs/{id}/end                              → 200

PUT  /playthroughs/{pid}/saves/{slotNo}
     {chapterId, chapterVersion, currentEpisodeId, snapshot: <JSON>, playSeconds, deviceKey}
                                                          → 200 {revision, updatedAt}
GET  /playthroughs/{pid}/saves                           → 200 [{slotNo, chapterId, chapterVersion, currentEpisodeId, revision, playSeconds, updatedAt, device}]
GET  /playthroughs/{pid}/saves/{slotNo}                  → 200 {..., snapshot} | 404
```

**완료 기준**
- PUT → revision 1. 같은 슬롯 PUT → revision 2.
- 없는 `chapterVersion` PUT → 404 (FK 위반 500이 아님 — 서비스에서 먼저 조회).
- GET으로 받은 `snapshot`이 올린 것과 의미상 동일.
- `deviceKey` 두 종류로 올리면 `devices`에 두 행.

**하지 않는 것**
- 스냅샷 내부 검사 (`nodeName`, 스탯 등 어떤 키도 읽지 않는다).
- 충돌 감지 (M4). 지금은 마지막 PUT이 이긴다.
- 슬롯 개수 제한 `[결정 필요]` — 기본 3.

**함정**
- `ON DUPLICATE KEY UPDATE`에서 `revision = revision + 1`은 신규 INSERT 시 실행되지 않는다. 신규 행의 revision은 1이 되도록 INSERT 값에 1을 준다.
- `snapshot`은 `String`으로 받는다. 요청 DTO에서 `JsonNode`로 받으면 Jackson이 재직렬화하므로 원본 보존이 깨질 수 있다. `@RequestBody`를 `JsonNode` 전체로 받고 `snapshot` 부분만 `.toString()`하지 말 것 — 대신 `snapshot`을 `JsonNode`로 받아 `writeValueAsString`. (M1의 JSON 정규화 결정과 같은 문제.)

---

### M3. 선택 이력·이벤트 로그

**목표**: 세이브 업로드 한 요청에서 세 테이블이 함께 쓰이고, 하나라도 실패하면 전부 롤백된다. [1] 영구 계층이 실제 데이터로 선다.

**작업**
1. M2의 PUT 요청 확장:
   ```
   choices: [{seq, episodeId, optionIndex, chosenAt}]      // 마지막 업로드 이후 증분
   events:  [{episodeId, occurredAt}]                       // EventKey가 붙은 에피소드를 다 본 것
   ```
2. `events`의 `eventKey`는 클라가 보내지 않는다. 서버가 `chapter_episodes.event_key`에서 찾는다. 비어 있으면 400 (EventKey 없는 에피소드에 이벤트를 붙이려 함).
3. 서비스 안 순서: 슬롯 upsert → `choice_history` 배치 INSERT → `event_log` 배치 INSERT. 하나의 `@Transactional`.
4. 조회 API.

**API**
```
PUT  /playthroughs/{pid}/saves/{slotNo}       (M2 + choices, events)   → 200 {revision, acceptedChoices, acceptedEvents}
GET  /playthroughs/{pid}/events                                         → 200 [{eventKey, chapterId, chapterVersion, episodeId, occurredAt}]
GET  /playthroughs/{pid}/saves/{slotNo}/choices?afterSeq=N              → 200 [{seq, episodeId, optionIndex, chosenAt}]
```

**완료 기준**
- 선택 3개 + 이벤트 1개 동봉 PUT → 세 테이블 모두 기록.
- `choices` 중 하나의 `episodeId`를 존재하지 않는 값으로 → 400, **세 테이블 모두 변화 없음** (revision도 안 오름).
- 이벤트를 EventKey 없는 에피소드에 붙임 → 400.
- `GET /events`가 챕터 표시명과 함께 나온다 (JOIN `chapter_contents`, `chapter_episodes`).

**하지 않는 것**
- 재전송 흡수 (M4). 같은 seq를 두 번 보내면 지금은 409로 끝난다.
- 선택 이력과 스냅샷의 **일관성 검사** (스냅샷의 현재 에피소드가 마지막 선택의 도착지인가 등). 서버는 스냅샷을 열지 않는다.

**함정**
- 배치 INSERT는 `JdbcClient`가 직접 지원하지 않는다. 루프로 돌리거나 `NamedParameterJdbcTemplate.batchUpdate`를 쓴다. 후자를 권장, 이유를 주석으로.
- `chosenAt`/`occurredAt`은 클라 시각이다. 서버 시각으로 덮지 않는다 (오프라인 플레이 반영). `received_at`이 서버 시각.
- `event_log` UNIQUE 위반(같은 이벤트 재기록)은 M4까지는 409.

---

### M4. 멱등성과 충돌

**목표**: 네트워크 재시도와 두 기기 동시 플레이를 락 없이 데이터로 처리한다. 이 마일스톤이 이 프로젝트의 핵심 학습.

**설계**

요청에 `baseRevision` 추가 (클라가 마지막으로 서버에서 받은 revision).

```
서버 revision == baseRevision       → 정상 적용. revision+1.
서버 revision == baseRevision + 1
  AND 동봉 choices 전부가 이미 존재  → 재전송. 아무것도 쓰지 않고 현재 상태 반환 (200, replayed=true).
그 외                               → 409 Conflict. 응답에 서버 측 요약(revision, updatedAt, device, currentEpisodeId, playSeconds).
```

409를 받은 클라는 둘 중 하나를 한다:
- 서버 것을 채택 → `GET` 후 로컬 교체.
- 내 것을 채택 → `?force=true`와 `baseRevision=<서버 revision>`으로 재요청. 서버는 force 요청을 정상 적용하되 `event_log`/`choice_history`의 UNIQUE 충돌은 **무시**하고 새 것만 추가 (`INSERT IGNORE` 또는 사전 조회).

**구현의 핵심 한 줄**
```sql
UPDATE save_slots SET ..., revision = revision + 1
WHERE id = :id AND revision = :baseRevision
```
영향 행 수가 0이면 충돌. 이것이 낙관적 동시성이며 락을 잡지 않는다.

**완료 기준**
- 같은 요청 두 번 → 두 번째는 200 + `replayed=true`, 테이블 변화 없음.
- 기기 A가 revision 3에서 올리고, 기기 B가 baseRevision 2로 올림 → B는 409 + A의 요약.
- B가 force 재요청 → 200, B의 스냅샷이 서버에, A의 choice_history는 남아 있고 B의 새 것이 추가됨.
- **동시 요청 테스트**: 두 스레드가 같은 baseRevision으로 동시에 PUT → 정확히 하나만 200, 하나는 409. (테스트 코드에 `ExecutorService`로 작성.)

**하지 않는 것**
- 세이브 병합 (두 기기의 선택 이력을 합쳐 하나의 스냅샷으로). 스냅샷은 통째로 교체만 한다.
- 비관적 락, `SELECT ... FOR UPDATE`. 필요하지 않음을 완료 기준이 증명한다.

**함정**
- `replayed` 판정에서 "choices 전부가 이미 존재"는 `(save_slot_id, seq)`로 확인한다. choices가 비어 있는 재전송(스냅샷만)은 판정이 불가능하므로 → 이 경우 409 대신 **200 + 현재 상태**를 준다. `[결정 필요]` 클라에 요청 UUID를 붙여 완전한 멱등 키로 쓸지.
- 트랜잭션 격리 수준 기본(REPEATABLE READ)에서 위 UPDATE 패턴은 안전하다. 격리 수준을 바꾸지 않는다.

---

### M5. 조회와 집계

**목표**: 관계형 DB의 보상. JOIN과 GROUP BY로 게임 데이터를 읽고, 인덱스가 필요해지는 순간을 EXPLAIN으로 본다.

**작업** — 각 쿼리는 SQL 파일(`src/main/resources/sql/stats/*.sql`)로 두고 레포지토리가 읽는다. 사용자가 Workbench에서 그대로 실행해볼 수 있게.

1. 회차 진행 요약: 슬롯별 챕터·에피소드·플레이 시간·마지막 기기.
2. EventKey 도달률: `event_log GROUP BY event_key` / 전체 회차 수.
3. 선택지 선택 비율: `choice_history GROUP BY chapter_content_id, episode_id, option_index` — `ChoiceLabel`은 `chapter_contents.body`에서 `JSON_EXTRACT`로 붙인다 (해석이 아니라 표시용 라벨 조회).
4. 유저별 최근 플레이: `save_slots.updated_at` 기준.

**API**
```
GET /stats/events                              → [{eventKey, playthroughs, ratio}]
GET /stats/chapters/{chapterId}/choices        → [{version, episodeId, optionIndex, label, count, ratio}]
GET /users/{userId}/summary                    → {playthroughs, lastPlayedAt, totalPlaySeconds, events: [...]}
```

**완료 기준**
- 회차 20개, 선택 200개 정도의 더미 데이터를 넣는 스크립트(`db/seed.sql`)가 있다.
- 위 세 API가 더미 데이터에 대해 맞는 숫자를 낸다 (Workbench 수작업 계산과 대조).
- `EXPLAIN`을 붙여 각 쿼리가 full scan을 하는지 확인하고, 하면 인덱스를 추가한 뒤 다시 EXPLAIN. 전후를 `docs/M5-explain.md`에 기록.

**하지 않는 것**
- 캐시. 집계가 느려도 더미 데이터 규모에선 문제가 아니다.
- 실시간 대시보드 UI.

---

### M6. 마감

**목표**: Unity 클라가 붙어도 되는 상태.

**작업**
1. 비밀번호 해시: `spring-security-crypto`만 추가 (전체 Security 아님), `BCryptPasswordEncoder`.
2. 로그인·세션: `POST /auth/login` → 토큰 발급, `sessions` 테이블 (token, user_id, expires_at). 이후 모든 `/playthroughs/**`, `/users/{id}/**`는 `Authorization: Bearer` 필수, 남의 자원 접근 시 403. 인터셉터 하나로 구현.
3. 콘텐츠 수입 API(`POST /content/**`)는 별도 관리자 키 (`X-Admin-Key` 헤더, properties에 값).
4. 에러 응답 통일: `{code, message, detail?}`. 모든 4xx/5xx가 이 형식.
5. `event_log` UNIQUE 범위 확정 `[결정 필요]`: (a) 회차 내 EventKey당 1회 — 챕터 버전 무관, (b) 현재대로 버전별 1회.
6. Flyway 도입 여부 `[결정 필요]`.
7. README 갱신: 실행법, API 목록, 마일스톤 상태.

**완료 기준**
- 토큰 없이 세이브 API → 401. 남의 회차 → 403.
- DB에 평문 비밀번호가 없다.
- 모든 에러 응답이 같은 형식.

**하지 않는 것**
- OAuth, 소셜 로그인, 리프레시 토큰. 게스트 계정 + 단순 토큰으로 충분.
- HTTPS 설정 (배포 시 리버스 프록시의 일).

---

### M7. Unity — 저장 포트 구현

**목표**: `ked-presentation-runtime`이 비워둔 `ISaveStore`를 채운다. 로컬 우선, 서버는 동기화.

**작업** (Unity 저장소 쪽, 이 서버 저장소 밖)
1. `ISaveStore` 인터페이스 확정: `Save(slot, snapshot)`, `Load(slot)`, `List()`.
2. `LocalFileSaveStore`: `Application.persistentDataPath`에 JSON.
3. `SyncQueue`: 선택 커밋마다 `{seq, episodeId, optionIndex, chosenAt}`와 EventKey 발생을 로컬 큐에 적재. 스냅샷은 항상 최신 것 하나만.
4. `ServerSyncSaveStore`: 네트워크 가능 시 큐를 M3/M4 형식으로 PUT. 성공하면 큐 비우고 `revision` 저장. 409면 M8로.
5. `ProgressionDriver`의 `Commit` 직후가 저장 시점. 드라이버 자체는 건드리지 않고, 호스트(`ProgressionLauncher`)가 커밋 이벤트를 받아 저장.

**서버 쪽 확인 사항**: M3 요청 형식이 Unity에서 만들기 쉬운지. `chosenAt`은 ISO-8601 UTC.

**완료 기준**
- 비행기 모드로 선택 5개 → 온라인 → 서버 `choice_history`에 5행, seq 연속.
- 앱 강제 종료 후 재실행 → 로컬에서 이어짐, 큐 유지.

---

### M8. Unity — 복구와 충돌 UI

**작업**
1. 로그인 후 `GET /playthroughs/{pid}/saves`로 서버 슬롯 목록 → 로컬에 없으면 내려받아 복구.
2. 409 수신 시: 양쪽 요약(에피소드, 플레이 시간, 기기, 시각)을 보여주고 선택. "서버 것" → GET 후 로컬 교체 + 큐 폐기. "내 것" → force 재요청.

**완료 기준**: 두 기기(또는 에디터+빌드) 시나리오가 끝까지 동작.

---

### M9 (선택)

- 콘텐츠 버전 업 시 세이브 마이그레이션 정책 (같은 `chapter_id`의 새 버전으로 옮길 수 있는가, 에피소드 ID가 유지되면 가능).
- 같은 기능을 JPA로 다시 구현해 `JdbcClient`와 비교.
- 관리자 통계 화면.

---

## 5. 열린 결정 목록

| # | 결정 | 기본 권장 | 언제 |
|---|---|---|---|
| 1 | `body` 컬럼 JSON vs LONGTEXT | JSON, diff는 의미 비교 | M1 |
| 2 | 슬롯 개수 제한 | 3 | M2 |
| 3 | 요청 UUID 멱등 키 도입 | choices 기반으로 시작, 부족하면 도입 | M4 |
| 4 | `event_log` UNIQUE 범위 | (a) 회차 내 1회 | M6 |
| 5 | Flyway | M6에서 도입 | M6 |
| 6 | Testcontainers | 사용자 Docker 환경에 따라 | M0 |
