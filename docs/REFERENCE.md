# REFERENCE — 명령·SQL·테스트·검증 대조표, 그리고 숙련자라면 어떻게 두었을까

> [`RUNBOOK.md`](RUNBOOK.md) 가 "어떻게 하나"라면 이 문서는 **"그 명령이 정확히 무엇을 하고, 무엇이 그것을 증명하나"** 다.
> §1 명령 · §2 SQL · §3 테스트 지도 · §4 검증 방식(차후에 다시 하려면) · §5 10년차라면 여기를 어떻게 두었을까.
> 기준 시점 2026-09-02 (M9 종료, 테스트 120건, V1~V6).

---

## 1. 명령어 — 정확한 동작

### 1.1 Gradle

| 명령 | 정확히 무엇을 하나 | 확인은 |
|---|---|---|
| `.\gradlew.bat bootRun` | Gradle 래퍼(레포에 고정된 9.5.1)를 내려받아 → `bootRun` 태스크: main 소스 컴파일 → `SpringPrepareApplication` 을 **자식 JVM** 으로 기동. `application.properties` 가 `spring.profiles.active=local` 이라 `application-local.properties` 를 겹쳐 읽는다 → Hikari 풀이 `game` 에 접속 → **Flyway 가 `flyway_schema_history` 와 `db/migration/V*.sql` 을 대조해 빠진 버전을 적용** → Tomcat 8080. 터미널을 붙든다. PS 5.1 의 Ctrl+C 는 Gradle 프로세스만 끊고 자식 JVM 은 남길 수 있다 — 그래서 `Stop-Ked` | 기동 로그 `Started SpringPrepareApplication`, `Test-Ked`, `SELECT * FROM game.flyway_schema_history` |
| `.\gradlew.bat cleanTest test` | `cleanTest` = `build/test-results`·`build/reports/tests` 삭제 → `test` 가 "입력이 안 바뀌었다(up-to-date)"로 건너뛰지 못하게. `test` = main·test 컴파일 → JUnit Platform 실행. `@SpringBootTest` 클래스는 **같은 설정끼리 컨텍스트 하나를 공유**(캐시)하므로 기동은 몇 번뿐이고, `@ActiveProfiles("test")` 로 `application-test.properties`(`game_test`) 를 읽는다. 첫 컨텍스트 기동에서 Flyway 가 `game_test` 를 마이그레이션한다(F42). 각 테스트의 `@BeforeEach` 가 `DbCleaner` 로 전부 지우고 시작하고, `StatsApiTest` 만 `@Sql(seed)` 로 매 메서드 전에 seed 를 다시 넣는다. `build.gradle` 의 `testLogging { events 'passed','failed','skipped' }` 가 이름을 한 줄씩 찍는다 | 출력 끝 `N tests completed` 와 **`executed`**(F21). 실패 시 `build/reports/tests/test/index.html` |
| `… test --tests "com.sparta.springprepare.stats.*"` | 패키지·클래스·메서드 필터. `--tests "*PlaythroughApiTest.같은_클라*"` 처럼 메서드 이름도 된다(한글 그대로) | 한 클래스만 빨리 돌릴 때 |
| `.\gradlew.bat clean` | `build/` 전부 삭제. 컴파일 결과가 이상할 때만 | — |

### 1.2 API 호출 — `scripts/api.ps1` (= check 문서의 `Call-Api`)

| 함수 | 보내는 것 | 돌아오는 것 |
|---|---|---|
| `Ked-Connect -Base -AdminKey` | 아무것도 안 보낸다. `$KED` 에 base·키 저장 | — |
| `Ked-Login u p` | `POST /auth/login {username,password}` → 401 이면 `POST /users` 로 가입 후 다시 로그인. 토큰·userId 를 `$KED` 에 | 토큰(CHAR 64 hex, `sessions` 행), 24h |
| `Ked M /path [json]` | `Invoke-WebRequest` + `Authorization: Bearer …`. 본문은 **UTF-8 바이트**로(`charset=utf-8`), 본문이 없으면 Content-Type 을 아예 안 보낸다(Unity 와 같은 모양) | `"상태`n본문"`. 본문은 `RawContentStream` 을 UTF-8 로 직접 디코딩(F: PS 의 ISO-8859-1 회피). 4xx/5xx 도 같은 모양 — throw 하지 않는다 |
| `Ked-Admin M /path [json]` | 같되 `X-Admin-Key` | 같음 |
| `Ked-Import 파일` | `POST /content/chapters` **`-InFile`** — 파일 바이트를 재인코딩 없이 그대로. 서버는 그 바이트의 SHA-256 을 checksum 으로 저장 | 201(새 버전) / 200(같은 checksum 이 이미 있음) |
| `Test-Ked` | `GET /content/chapters`(공개) 3초 타임아웃 | 살아 있음 / 죽어 있음 |
| `Stop-Ked` | `netstat -ano` 에서 `:8080 … LISTENING <pid>` → `taskkill /PID <pid> /F` | 죽인 PID |

`Invoke-WebRequest -UseBasicParsing` 은 IE 파서를 안 쓰게 하는 옵션(PS 5.1 필수). `-InFile` 은 스트림을 그대로 흘리므로 인코딩이 개입할 자리가 없다 — `Get-Content` 는 개입한다(F45).

### 1.3 프로세스·DB 클라이언트

| 명령 | 동작 |
|---|---|
| `Invoke-RestMethod http://localhost:8080/content/chapters` | 공개 GET 한 번. 연결 거부 = 서버 없음. `Test-Ked` 의 손 버전 |
| `netstat -ano \| findstr :8080` → `taskkill /PID n /F` | 포트를 잡은 PID 를 찾아 강제 종료. `bootRun` 의 자식 JVM 이 남았을 때 |
| Workbench ⚡⚡ (Execute All) | 스크립트 전체를 **현재 기본 스키마**에서 실행. seed 는 스키마 이름을 안 붙이므로(양쪽 DB 공용) 기본 스키마가 `game` 이어야 한다 — 좌측 더블클릭, `SELECT DATABASE();` |
| `SET SQL_SAFE_UPDATES = 0;` | Workbench 세션의 안전 모드 해제(WHERE 없는 DELETE 허용). **세션 변수는 커넥션에 붙는다** — seed 끝에서 1 로 되돌리면 앱 풀이 오염된다(F34). 되돌리지 않는다 |
| `git stash` / `git checkout` | 추적 파일만 옮긴다. **gitignore 된 설정 파일은 따라오지 않는다** — 오늘의 110건 실패(RUNBOOK §7 첫 줄) |

### 1.4 Unity (에디터)

키 **4** 이어하기(4번은 세이브가 있으면 재개, 없으면 새 게임) · **5** 새 게임(큐 flush → 새 guid → 빈 큐) · **6** 즐겨찾기(즉시 PUT) · **7** 마지막 즐겨찾기로 갈라지기 · 백로그 항목 클릭 = 그 장면으로 갈라지기(flush → 새 guid + `forkedFrom`). 콘솔 접두사 `[계정] [동기화] [저장] [즐겨찾기] [복구]`.

## 2. SQL — 정확한 동작

### 2.1 마이그레이션 (Flyway, `src/main/resources/db/migration/`)

| 버전 | 무엇 | 왜 |
|---|---|---|
| V1 `init` | 테이블 9: users · devices · game_definitions · chapter_contents(body JSON, checksum CHAR(64) UNIQUE) · chapter_episodes(복합 PK) · playthroughs · save_slots(UNIQUE(playthrough_id, slot_no), revision) · choice_history(UNIQUE(save_slot_id, seq), FK 복합) · event_log | `docs/schema.sql` 을 그대로. `CREATE DATABASE`·`USE` 는 뺐다 — 접속 URL 의 스키마에서 돈다(D-012) |
| V2 | `game_definitions.checksum` 추가 | 같은 파일 재수입 = 200 (D-007) |
| V3 | `event_log(event_key, playthrough_id, occurred_at)`, `choice_history(chapter_content_id, episode_id, option_index)` 인덱스 추가, 중복되는 단일 인덱스 DROP | M5 집계. `rows` 가 아니라 `type`/`Using temporary` 가 바뀌었다(F36) |
| V4 | `event_log` UNIQUE 를 `(playthrough_id, event_key)` 로 축소 — **DROP+ADD 한 문장** | FK 를 지탱하는 유일한 인덱스는 단독 DROP 불가(1553, F39) |
| V5 | `sessions(token CHAR(64) PK, user_id FK, expires_at)` | 토큰 = 행이 있으면 유효 (D-013 계보) |
| V6 | playthroughs +`client_id`·`forked_from_id`(self FK)·`forked_from_client_id`·`forked_scene_index`, UNIQUE(user_id, client_id) / save_slots +시간 둘·`chapter_completed` / `bookmarks` 신설 | M8-A. 전부 추가만 — 옛 행이 산다 |

규칙: 적용된 파일은 고치지 않는다(checksum 불일치로 기동 실패). `baseline-on-migrate` 를 켜지 않는다 — "Found non-empty schema" 는 DB 를 다시 만들라는 신호다.

### 2.2 앱이 실행하는 문장 — 결정이 실린 것들

**콘텐츠**
- `NEXT_VERSION`: `SELECT COALESCE(MAX(version), 0) + 1 … WHERE chapter_id = ?` — 읽고 나서 INSERT 하는 두 문장이라 틈이 있지만 `UNIQUE(chapter_id, version)` 이 막고 409 가 난다. 관리자 하나가 쓰는 경로라 그걸로 충분하다.
- `SELECT_BY_CHECKSUM`: 같은 바이트면 있는 행을 돌려준다(200) — 멱등 수입의 전부.
- `INSERT_EPISODE` 는 `NamedParameterJdbcTemplate.batchUpdate` — `JdbcClient` 는 배치가 없다. 배치 안의 UNIQUE 위반도 `DuplicateKeyException` 으로 번역된다(F10).

**회차**
- `INSERT (user_id, client_id, forked_from_id, forked_from_client_id, forked_scene_index)`; `SELECT_ID_BY_CLIENT`: `WHERE user_id = ? AND client_id = ?` — 멱등 키.
- `BACKFILL_CHILDREN`: `UPDATE playthroughs SET forked_from_id = :parentId WHERE user_id = ? AND forked_from_client_id = :parentClientId AND forked_from_id IS NULL` — 부모가 나중에 와도 자식이 닫힌다. `IS NULL` 조건이 있어 이미 닫힌 것은 건드리지 않는다.
- `END`: `UPDATE … SET ended_at = CURRENT_TIMESTAMP WHERE id = ? AND ended_at IS NULL` — 0행이 실패가 아니다(멱등). D-025 로 통계 의미는 없다.
- `SELECT_SUMMARIES`: `playthroughs p LEFT JOIN save_slots s ON … AND s.slot_no = 1 LEFT JOIN chapter_contents c` + 상관 서브쿼리 둘(슬롯 수, 즐겨찾기 수). 슬롯을 1 로 한정해 행이 곱해지지 않는다. `RowMapper` 로 `forkedFrom` 을 중첩 객체로 조립 — 이 프로젝트의 유일한 수동 매퍼.

**세이브 — 낙관적 동시성의 실물**
- `INSERT … revision = 1` (DEFAULT 0 에 맡기면 첫 업로드가 0 이 된다).
- `UPDATE_IF_REVISION`: `UPDATE save_slots SET …, revision = revision + 1 WHERE playthrough_id = ? AND slot_no = ? AND revision = :baseRevision` — **영향 행 수 1 = 내가 알던 그대로였다, 0 = 그 사이 누가 바꿨다.** REPEATABLE READ 에서도 UPDATE 의 WHERE 는 최신 커밋본을 읽고 행 락을 잡으므로(current read) 두 트랜잭션 중 하나만 통과한다(F30). 판정은 조회가 아니라 이 문장의 반환값으로.
- `SELECT_STATE`: 성공 뒤 `id, revision, updated_at` 재조회 — 앱이 계산하지 않고 DB 의 사실을 읽는다.
- `SELECT_EXISTING_SEQS`: `WHERE save_slot_id = ? AND seq IN (:seqs)` — 재전송 판정(전부 있으면 replayed) 과 force 의 필터. 300 개 IN 도 문제없다.
- choice·event `INSERT` 는 배치. `(save_slot_id, seq)`·`(playthrough_id, event_key)` UNIQUE 가 마지막 방어선.
- `DeviceRepository.UPSERT`: `INSERT … ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP` — 남아 있는 유일한 ON DUPLICATE. 갱신 경로에서도 AUTO_INCREMENT 를 소비한다(F18) — devices 에서는 상관없다.

**즐겨찾기**
- `SELECT_ID` 는 **삭제된 것도** 찾는다 → 같은 id 재PUT 이 `UPDATE … deleted_at = NULL` 로 부활한다(200). 조건 없는 UPDATE — 지킬 누적 상태가 없다.
- `SOFT_DELETE`: `UPDATE … SET deleted_at = CURRENT_TIMESTAMP WHERE … AND deleted_at IS NULL` — 두 번째도 0행, 실패 아님(204).
- `BACKFILL_PLAYTHROUGH`: 회차 되채우기의 즐겨찾기판.

**세션**: `INSERT (token, user_id, expires_at)` / `SELECT_BY_TOKEN` / `DELETE` — 만료 판정은 앱이 UTC 끼리 비교.

### 2.3 집계 SQL (`src/main/resources/sql/stats/*.sql`) — 파일 머리 주석이 정의의 정본

| 파일 | 핵심 구문 | 조용히 틀리는 자리 |
|---|---|---|
| `event_reach.sql` | `COUNT(DISTINCT playthrough_id)` / `(SELECT COUNT(*) FROM playthroughs)` 스칼라 서브쿼리 분모 / `ROUND(x*100.0/NULLIF(분모,0), 1)` | 분모 정의(전체 vs 종료) — SQL 이 아니라 물음의 문제 |
| `choice_ratio.sql` | `JSON_TABLE(body, '$.Nodes[*]' … NESTED PATH '$.NextOptions[*]' … FOR ORDINALITY)` 로 라벨을 (에피소드, 옵션번호) 행으로 펼침 → `option_ordinal - 1` / `SUM(COUNT(*)) OVER (PARTITION BY episode_id)` 윈도로 에피소드 합 | **`- 1`** 을 빠뜨리면 라벨만 한 칸 밀리고 숫자는 전부 멀쩡하다 — 라벨과 번호를 짝지어 단언하는 이유 |
| `user_summary.sql` | `users LEFT JOIN playthroughs LEFT JOIN save_slots` — 곱해진다 → 세는 것은 `COUNT(DISTINCT)`, **더하는 것은 스칼라 서브쿼리**(`SUM(DISTINCT)` 는 같은 값을 합쳐 버린다) | 팬아웃. `forks`(client id 기준), `completedPlaythroughs`(슬롯 1 chapter_completed) |
| `chapter_overview.sql` | `chapter_contents LEFT JOIN save_slots(slot_no=1) LEFT JOIN playthroughs`, `COALESCE(ROUND(…), 0.0)` | 회차 0 이면 0.0 (NULL 아님). 없는 버전은 0행 → 서비스가 404 |

### 2.4 `db/seed.sql`

전부 지우고(자식 → 부모, `forked_from_id` 먼저 NULL) AUTO_INCREMENT 를 1 로 → 사용자 5 · 회차 20(1~12 종료) · 슬롯 30(11~20 은 둘) · 선택 200(회차마다 슬롯 1 에 seq 1~10, EP01 50/30/20 · EP03_02 60/40 · EP02_01 100) · 이벤트 27(MIDPOINT 15, ENDING_A 8, ENDING_B 4). 난수 없음 — CASE 식이 설계표다. 그래서 `StatsApiTest` 의 상수가 손으로 나온다.

### 2.5 진단 질의 — 자주 쓰는 것

```sql
SELECT DATABASE();                                                        -- Workbench 기본 스키마
SELECT version, description, success FROM game.flyway_schema_history;     -- 어디까지 적용됐나
SET @PT = 32;  SELECT ch.seq, ch.episode_id, ch.option_index, ch.chosen_at
  FROM choice_history ch JOIN save_slots s ON s.id = ch.save_slot_id
  WHERE s.playthrough_id = @PT ORDER BY ch.seq;                           -- 이 회차만 (필터 없이 보지 않는다)
SELECT id, client_id, forked_from_id, forked_from_client_id FROM playthroughs WHERE user_id = @U;
SHOW INDEX FROM event_log;   EXPLAIN SELECT …;                            -- 인덱스가 있나 / 쓰이나 (type, Extra)
```

### 2.6 `DbCleaner` — 삭제 순서가 FK 그래프다

`UPDATE playthroughs SET forked_from_id = NULL` → sessions → bookmarks → choice_history → event_log → save_slots → playthroughs → chapter_episodes → chapter_contents → game_definitions → devices → users. TRUNCATE 가 아니라 DELETE 인 이유: MySQL 은 FK 로 참조되는 부모에 TRUNCATE 를 거부한다. 새 테이블을 더하면 이 목록과 seed 의 DELETE 목록 둘 다 갱신한다.

## 3. 테스트 지도 — 무엇이 무엇을 증명하나

### 3.1 방식

- 전부 `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")` — **실제 컨텍스트, 실제 MySQL(`game_test`), 가짜는 없다.** HTTP 만 MockMvc(서블릿 컨테이너 없이 디스패처 직접 호출). 그래서 PS 5.1 의 헤더 기본값 같은 "전송 층" 차이는 못 본다(F47) — 그건 check 문서 몫.
- 준비는 `Fixtures`(SQL 직접 INSERT — API 가 깨져도 무관한 테스트가 안 깨지게), 인증은 `AuthSupport.login`(실제 `/auth/login` 을 쳐서 토큰). 정리는 `DbCleaner`. `StatsApiTest` 만 `@Sql("file:db/seed.sql", encoding UTF-8)`.
- 기대값은 **코드를 돌리기 전에** 적는다(M5 규칙) — 특히 숫자.

### 3.2 보장 → 테스트

| 보장하는 것 | 테스트 (클래스 › 메서드) | 어떻게 |
|---|---|---|
| 같은 파일 재수입은 200, 행이 늘지 않는다 | `ChapterContentApiTest › 같은_파일을_다시_올리면_200이고_행이_늘지_않는다` / `바이트가_한_글자라도_다르면_새_버전이다` | 같은 바이트 두 번, 한 글자 바꿔 한 번 |
| checksum = SHA-256 hex 64 | `ChecksumTest` 5건 (표준 벡터, 줄바꿈 차이) | 순수 단위 |
| 수입은 한 트랜잭션 | `ChapterContentApiTest › 색인_INSERT가_실패하면_본문도_남지_않는다` | 같은 EpisodeId 둘 → 복합 PK 가 막는다(테스트용 예외 코드 없이 실제 제약으로) → `chapter_contents` 0행 |
| 콘텐츠 POST 는 관리자만, GET 은 공개 | `키_없이_콘텐츠_POST는_401이다` / `콘텐츠_GET은_키_없이_공개다` | |
| 첫 업로드 revision 1, 재업로드 2 | `SaveSlotApiTest › 첫_업로드는_revision_1이고_다시_올리면_2가_된다` | |
| 스냅샷은 열지 않고 의미 보존 | `SaveSlotApiTest › 스냅샷은_의미가_보존된_채_돌아온다` | `readTree` 로 트리 비교(바이트 아님) |
| 슬롯 1..127, 상한 없음 | `범위_밖_슬롯_번호는_400이다` / `슬롯_개수에는_상한이_없다` | 0·128 → 400, 1·2·3·5·42·127 성공 |
| 선택·이벤트가 한 요청에 기록, 실패하면 셋 다 그대로 | `SaveHistoryApiTest › 선택_3개와_이벤트_1개가_한_요청에_기록된다` / `없는_에피소드가_섞이면_400이고_세_테이블이_그대로다` | 400 뒤 세 테이블 COUNT 와 revision 그대로(F29) |
| 오프셋이 붙어 와도 UTC 로 저장·회수 | `SaveHistoryApiTest › 오프셋이_붙어_와도_UTC로_저장되고_UTC로_돌아온다` | `+09:00` 보내고 `DATE_FORMAT` 으로 DB 벽시계를 직접 읽는다(왕복 아님) |
| 재전송 = replayed, 아무것도 안 쓴다 | `SaveSlotConflictTest › 같은_요청을_다시_보내면_replayed고_아무것도_쓰지_않는다` / `seq가_하나라도_새것이면_재전송이_아니다` / `choices가_없으면_판정하지_않고_409다` | D-010 |
| 낡은 base 는 409 + 서버 상태, force 는 이력 새 것만 | `낡은_baseRevision은_409고_현재_서버_상태를_알려준다` / `force는_스냅샷을_덮고_이력은_새_것만_더한다` / `force여도_낡은_base면_409다` | |
| **동시 쓰기는 정확히 하나만 성공** | `SaveSlotConcurrencyTest › 동시에_같은_슬롯에_쓰면_정확히_하나만_성공한다` | 스레드 2 · `CountDownLatch` 로 동시 출발 · 10라운드(슬롯 1~10) · 매 라운드 200 하나·409 하나·revision 2 |
| 이벤트는 회차당 1회 흡수 | `SaveHistoryApiTest › 수입한_콘텐츠의_EventKey가_이벤트에_그대로_쓰인다`, `EventKey가_없는_에피소드에는_이벤트를_기록할_수_없다` | |
| 배치가 커져도 그대로 | `SaveHistoryApiTest › 장면_단위로_접힌_큰_배치도_한_요청에_다_들어간다` | 300 선택 + 2 이벤트, 다음 장면 이어 붙임 |
| 토큰: 없으면 401, 만료 401, 로그아웃 즉시 무효, 실패는 구분 안 됨 | `AuthApiTest` 7건 | 만료는 `sessions` 에 과거 `expires_at` 행을 SQL 로 직접 넣는다 |
| 남의 자원 403, 없는 것 404(존재 확인이 먼저) | `UserApiTest › 남의_id를_조회하면_403이다`, `PlaythroughApiTest › 남의_회차_종료는_403이다` / `없는_회차_종료는_404다`, `StatsApiTest › 남의_summary는_403이다` | |
| 모든 에러가 `{code, message}` — 404·405·깨진 JSON·타입 불일치·서비스 층 파싱 | `ErrorFormatTest` 5건 | F20·F43·F44 |
| 집계 숫자 = seed 설계표 | `StatsApiTest › 이벤트_도달률은_전체_회차를_분모로_한다` / `선택_비율은_에피소드마다_100퍼센트가_된다` / `라벨이_원본_JSON에서_옵션_번호에_맞게_붙는다` / `사용자_요약이_팬아웃_없이_센다` / `진행_중인_회차만_가진_사용자는_종료가_0이다` / `챕터_개요는_seed에서_스무_회차_완주_0이다` | 상수는 seed 설계표에서 손으로 |
| 통계 API 는 관리자 키만(사용자 토큰이 있어도 401) | `StatsApiTest › 관리자_키_없이_stats는_401이다` | D-013 |
| 회차 생성 멱등, 갈래 되채우기, 서버 id 무시, 옛 POST 400 | `PlaythroughApiTest` 16건 — `같은_클라_id로_다시_만들면_200이고_같은_회차다`, `자식이_먼저_오고_부모가_나중에_와도_링크가_닫힌다`, `부모의_서버_id는_요청이_아니라_조회로_정한다`, `본문_없는_옛_POST는_400이다`, `목록은_슬롯_1의_요약과_수_둘을_팬아웃_없이_준다` | |
| 즐겨찾기 upsert·soft delete·부활·순서 독립·403·404·413 | `BookmarkApiTest` 8건 | |
| 413 경계 1,048,576 / +1, 413 은 아무것도 안 쓴다 | `SaveSlotApiTest › 스냅샷은_1_048_576_바이트까지_받고_하나_넘으면_413이다` | 직렬화 바이트 정확히 |
| 완주율·갈래·즐겨찾기 수, 버전 분리, 요약 completed | `ChapterOverviewApiTest` 3건 | 손으로 놓은 데이터 2/1/50.0/1/1 |
| null 은 키가 있고 값이 null | `PlaythroughApiTest`·`BookmarkApiTest` 의 `value(nullValue())` | `doesNotExist()` 는 null 도 통과(F46) — 쓰지 않는다 |

### 3.3 테스트가 못 보는 것 → 어디서 보나

| 못 보는 것 | 어디서 |
|---|---|
| 실제 Tomcat·헤더 기본값·PS 5.1 인코딩 | `M{n}-check.md` 의 PowerShell 절 (`api.ps1`) |
| Flyway 가 `game`(개발 DB)에 적용되는 것 | check §1 `bootRun` 로그, `flyway_schema_history` |
| 두 프로세스·두 기기 | Unity `m8b-check.md` §5 (saves 폴더 바꿔치기) |
| Unity 큐·복구·갈라지기 | `M7-check.md`, `m8b-check.md` |
| 화면 | `M9-check.md` §1 (seed 상수와 눈으로) |
| 인덱스가 실제로 쓰이나 | `M5-explain.md` EXPLAIN 기록 |

## 4. 검증 방식 — 차후에 다시 하려면

세 층이다. 아래로 갈수록 느리고, 위로 갈수록 자주 돈다.

1. **자동 테스트** — 코드가 바뀌면 무조건. `cleanTest test` 120건. 한 기능만 손댔으면 `--tests` 로 그 클래스 + 반드시 전체 한 번.
2. **check 문서** — 실제 HTTP 와 DB 를 눈으로. 각 M 의 `M{n}-check.md` 는 "조작 → 기대(정확한 값)" 표라 그대로 다시 돌릴 수 있다. 명령은 `api.ps1` 로 바꿔 읽는다(`Call-Api POST $uri $body $AUTH` ≡ `Ked POST /path $body`). 결과는 그 문서의 결과 표에 날짜와 함께.
3. **Unity 실행** — 저장 층을 건드렸을 때만. `ked-presentation-runtime/docs/m8b-check.md`.

바꾼 것에 따라 어디까지 도나:

| 바꾼 것 | 1 | 2 | 3 |
|---|---|---|---|
| 집계 SQL·화면 | `StatsApiTest`·`ChapterOverviewApiTest` | M9-check §1 (seed) | — |
| 세이브 업로드 경로 | `SaveSlot*`·`SaveHistory*` 4클래스 | M4-check §4~§5, M8-check §5~§6 | Unity 4번·백로그 갈라지기 |
| 회차·즐겨찾기 계약 | `PlaythroughApiTest`·`BookmarkApiTest` | M8-check §2~§4 | Unity F6 스모크(m8b-check §1) |
| 마이그레이션 | 전체(첫 기동이 game_test 에 적용) | `bootRun` 로그 + `flyway_schema_history` | — |
| 인증·인터셉터 | `AuthApiTest`·`UserApiTest`·`ErrorFormatTest` | M6-check §3 | Unity 게스트 로그인 로그 |

원칙 넷: **기대값을 먼저 적는다** · **DB 질의는 거른다** · **실패 경로를 절반 이상** · **결과는 표에, 어긋난 것은 F 번호로**(사실이 문서를 고친 기록이 다음 사람의 함정 표가 된다).

## 5. 10년 이상 개발한 사람이라면 여기를 어떻게 두었을까

이 프로젝트가 학습용이라 일부러 안 한 것들이 있다(PLAN §2.2 "M6 전까지 의존성 금지" 같은). 실무에서 같은 것을 만든다면 **남길 것**과 **바꿀 것**이 갈린다.

### 5.1 그대로 남길 것 — 이미 실무 수준인 것

- **SQL 이 파일에 있고 머리 주석이 정의다.** ORM 이 있어도 집계는 이렇게 둔다. 숫자의 정의를 코드 리뷰에서 읽을 수 있다.
- **결정 기록(D)과 확인된 사실(F)에 번호가 있다.** ADR 이 바로 이것이다. PR 설명에 D 번호를 적게 하면 "왜"가 리뷰에서 사라지지 않는다.
- **기대값을 먼저 적는 seed.** 테스트 픽스처를 난수로 만들지 않는다.
- **락이 아니라 조건부 UPDATE.** 대부분의 팀이 `SELECT … FOR UPDATE` 나 `@Version` 으로 가는데, 한 문장 UPDATE 가 더 단순하고 더 검증돼 있다(F30).
- **클라와의 계약이 한 장의 정본 문서.** OpenAPI 가 있어도 "왜 이렇게 됐나"는 답신 문서가 한다.

### 5.2 바꿀 것 — 외우는 것을 없애는 순서대로

1. **명령을 코드로 만든다.** `RUNBOOK` 은 첫 단계다. 다음은 Gradle 태스크 또는 `scripts/` 하나로 — `gradlew smoke`(서버 켜고 M8-check §2~§6 을 `api.ps1` 로 자동 실행해 기대값을 단언), `gradlew seed`. 사람이 표를 보며 손으로 하는 절차 중 **결정적인 것은 전부 스크립트로**, 눈이 필요한 것(화면·Unity)만 사람이.
2. **테스트 DB 를 Testcontainers 로.** `game_test` + gitignore 된 `application-test.properties` 는 학습용 결정(D-002)이었다. Docker 에 MySQL 8 을 띄우는 Testcontainers 면 설정 파일이 없어지고, 어느 기계에서나 같고, **오늘의 "110건 실패" 자체가 존재하지 않는다.** 개발 DB 도 `docker-compose.yml` 하나.
3. **CI.** GitHub Actions 에서 push 마다 `cleanTest test` + `flyway validate`. 초록 배지가 아니라 **"executed N"** 을 잡는 스텝을 둔다(F21 을 CI 에 옮긴 것). check 문서의 결과 표는 CI 아티팩트로.
4. **비밀은 환경 변수로.** `SPRING_DATASOURCE_PASSWORD`, `APP_ADMIN_KEY` — Spring 은 프로퍼티 이름을 환경 변수로 자동 바인딩한다. `.example` 파일 대신 `.env.example` + 시크릿 매니저. gitignore 된 프로퍼티 파일은 "잃어버리는 것이 정상"인 물건이다.
5. **계약을 기계가 읽게.** 컨트롤러에서 OpenAPI(springdoc)를 뽑고, Unity DTO 를 거기서 생성하거나 최소한 대조 테스트를 둔다. 답신 문서(§1 계약)는 남기되 "정본"은 스펙 파일로 옮긴다. F46(null 규칙) 같은 것이 스펙에 `nullable: true` 로 박힌다.
6. **관측.** 요청마다 상관 id 를 로그에, Actuator `/actuator/health` 를 `Test-Ked` 대신, 409·413·401 을 카운터로. "서버가 틀린 말을 안 했다"는 것을 로그로 즉시 증명할 수 있어야 한다 — 오늘은 Workbench 를 열어 확인했다.
7. **마이그레이션 체크리스트.** PR 템플릿에 셋: 적용된 파일을 고치지 않았나 / FK 지탱 인덱스를 단독 DROP 하지 않았나(F39) / `DbCleaner`·seed 의 DELETE 목록을 갱신했나. 되돌리기 문장은 주석으로라도.
8. **파괴적 절차에 안전장치.** seed 머리에 `SELECT DATABASE()` 가 `game`·`game_test` 가 아니면 죽는 가드 한 줄. `DbCleaner` 는 URL 에 `_test` 가 없으면 예외.
9. **RUNBOOK 은 새 사람이 검증한다.** 문서가 맞는지는 쓴 사람이 아니라 처음 온 사람이 안다. 온보딩 첫날 과제 = RUNBOOK 따라가기 + 틀린 곳 고치기.
10. **죽은 개념은 생기는 순간 표시한다** (RETRO §5). `ended_at` 같은 것은 코드에 `@Deprecated` 급 주석과 D 번호를 같이 단다.

### 5.3 이 프로젝트에서 지금 할 값이 있는 것 — 셋

위 열 개를 다 하면 학습 프로젝트가 아니다. 지금 비용 대비 값이 큰 것은 **2(Testcontainers)·3(CI)·1 의 절반(`smoke.ps1`)** 이다. 셋 다 반나절 안이고, 하고 나면 이 문서 §1 의 함정 절반이 사라진다. 나머지는 이 서버가 실제 사용자를 받는 날 한다.
