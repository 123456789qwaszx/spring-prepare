# RUNBOOK — 처음 온 사람이 한 시간 안에 돌리는 법

> 이 문서는 **외우지 않기 위해** 있다. 명령은 전부 여기 있거나 `scripts/api.ps1` 에 있다.
> 배경·이유가 궁금하면 [`README.md`](README.md)(문서 지도) → 계획서·결정 기록으로. 여기는 "어떻게"만 적는다.
> 마지막 갱신: 2026-09-02 (M9 종료 시점). 무엇이 바뀌면 이 문서를 먼저 고친다 — check 문서가 아니라.

---

## 0. 준비물 (한 번)

| 무엇 | 확인 |
|---|---|
| JDK 17+ | `java -version` |
| MySQL 8.x (로컬, 3306) + Workbench | Workbench 로 root 접속 |
| PowerShell 5.1 (Windows 기본) | 함정이 셋 있다 — §7 |
| (Unity 쪽) Unity 에디터, `ked-presentation-runtime` 클론 | §6 |
| 이 레포 클론 | `C:\Users\<you>\Documents\GitHub\spring-prepare` |

## 1. DB 둘 만들기 (한 번) — 테이블은 만들지 않는다

Workbench 에서:

```sql
CREATE DATABASE game      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE game_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

테이블은 **Flyway 가 만든다** — `game` 은 첫 `bootRun` 에서, `game_test` 는 첫 테스트에서 V1~V6 이 자동 적용된다.
`docs/schema.sql` 을 손으로 돌리지 않는다(M6 이전 방식). 확인: `SELECT version, description FROM game.flyway_schema_history;`

## 2. 설정 파일 둘 (한 번) — **git 이 옮겨 주지 않는다**

둘 다 `.gitignore` 다. 클론·재체크아웃 뒤에 없어져 있으면 테스트가 110건씩 한꺼번에 넘어진다(`Failed to determine suitable jdbc url`).

```powershell
Copy-Item src\main\resources\application-local.properties.example src\main\resources\application-local.properties
Copy-Item src\test\resources\application-test.properties.example  src\test\resources\application-test.properties
```

각 파일에서 `CHANGE_ME` 둘을 채운다 — DB 비밀번호, 관리자 키. **test 쪽 url 은 반드시 `game_test`** (아니면 테스트가 개발 데이터를 지운다).

## 3. 기동·테스트·정지 — 매일

```powershell
cd C:\Users\<you>\Documents\GitHub\spring-prepare
.\gradlew.bat bootRun            # 터미널 하나를 차지한다. Flyway 로그 → Started …
.\gradlew.bat cleanTest test     # 반드시 cleanTest. 서버가 죽어 있을 때. 120건 (2026-09-02 기준)
```

- 래퍼는 `gradlew.bat` 이다 (`.cmd` 아님).
- `bootRun` 은 **Ctrl+C 로 안 죽는다.** 정지·확인은 `scripts/api.ps1` 의 `Stop-Ked` / `Test-Ked` (§4). 테스트 전엔 죽어 있어야 한다.
- 테스트 결과는 초록이 아니라 **`N executed`** 줄과 이름 목록을 읽는다 — `up-to-date` 는 0건 실행이다.

## 4. API 를 손으로 부르기 — `scripts/api.ps1`

PowerShell 창에서 한 번:

```powershell
. .\scripts\api.ps1                       # 함수 로드 (점 + 공백 + 경로)
Ked-Connect -AdminKey '<application-local 의 app.admin-key>'   # 기본 http://localhost:8080
Ked-Login m8 secret-pw                    # 없는 사용자면 가입까지. $KED.UserId / $KED.Token 에 남는다
```

그다음은 세 함수뿐이다:

| 함수 | 뜻 | 예 |
|---|---|---|
| `Ked GET /path` · `Ked PUT /path '{json}'` … | **Bearer 토큰** 붙여 호출. 상태 코드 한 줄 + 본문 한 줄 | `Ked GET "/users/$($KED.UserId)/playthroughs"` |
| `Ked-Admin GET /path` | **관리자 키** 붙여 호출 | `Ked-Admin GET /stats/events` |
| `Ked-Import <파일>` | 콘텐츠 수입 — `-InFile` 로 **바이트 그대로** (checksum) | `Ked-Import 'C:\…\Assets\@Dialogue\ChapterProgression\qwer.progression.json'` |

그리고 운영 셋: `Test-Ked`(살아 있나) · `Stop-Ked`(8080 을 잡은 프로세스를 죽인다) · `Ked-Seed`(seed 적용 안내 — Workbench 에서 한다).

각 M 의 검증 문서(`M{n}-check.md`)에 있는 `Call-Api …` 는 이 함수들의 옛 이름이다 — `Call-Api POST $uri $body $AUTH` ≡ `Ked POST /path $body`.

## 5. 관리자 화면·seed

- 화면: `http://localhost:8080/admin.html` — 키 한 번 입력(탭이 살아 있는 동안만 기억). 챕터·버전 고르면 개요·선택 비율·이벤트 도달률.
- seed(집계 학습용 더미, `db/seed.sql`): Workbench 에서 `game` 을 **더블클릭해 기본 스키마로** → File ▸ Open SQL Script ▸ ⚡⚡. **`game` 을 전부 비운다.** 기대 숫자는 `StatsApiTest` 상수(20 회차 · EP01 50/30/20 · 도달률 75/40/20).

## 6. Unity 붙이기

1. `ked-presentation-runtime` 을 에디터로 연다. `VNAppBootstrap` 인스펙터의 **저장·동기화** 헤더 `Server Base Url` = `http://localhost:8080` (비우면 로컬 저장만).
2. 서버에 **그 에디터의 에셋 파일 그대로** 수입돼 있어야 한다: `Ked-Import '<…>\Assets\@Dialogue\ChapterProgression\qwer.progression.json'`. 클라는 이 파일의 SHA-256 으로 버전을 찾는다 — 다른 사본을 올리면 동기화가 서는 것이 정상이다(D-015).
3. 플레이 모드. 키: **4** 이어하기 · **5** 새 게임 · **6** 즐겨찾기 · **7** 마지막 즐겨찾기로 갈라지기 · 백로그 클릭 = 그 장면으로 갈라지기.
4. 로컬 파일: `%USERPROFILE%\AppData\LocalLow\<Company>\<Product>\` 의 `account.json`(게스트 계정 — 잃으면 계정 상실), `saves\playthroughs\{guid}.json`·`{guid}.queue.json`, `active.json`, `bookmarks.json`.
5. "새 기기" 흉내 = 그 폴더를 비우고 `account.json` 만 되돌린다. "두 기기" = `saves\` 를 복사해 바꿔 끼운다.
6. 콘솔 로그 접두사: `[계정]` `[동기화]` `[저장]` `[즐겨찾기]` `[복구]`. `회차 생성(201)`/`확인(200)`, `완료 — revision N`, `충돌(409) … 갈라 이어 간다` 가 정상 경로다.

## 7. 함정 — 전부 여기 (각 M 이 실측으로 배운 것)

| 증상 | 원인 | 대응 |
|---|---|---|
| 테스트 110건이 한꺼번에 실패, `suitable jdbc url` | `application-test.properties` 없음(gitignore) | §2 |
| `gradlew.cmd` 없음 | 래퍼 이름 | `gradlew.bat` |
| 테스트가 0건 실행인데 `BUILD SUCCESSFUL` | `test` 만 돌림 | `cleanTest test` (F21) |
| 서버 껐다고 믿었는데 살아 있음 | `bootRun` 은 Ctrl+C 로 안 죽음 | `Test-Ked` → `Stop-Ked` |
| `Found non-empty schema … without schema history` | Flyway 이전 방식으로 만든 DB | DB 를 DROP 하고 §1 — **baseline-on-migrate 를 켜지 않는다**(D-012) |
| 콘텐츠 수입 뒤 Unity 가 `버전을 못 찾음` | `Get-Content` 로 보내 재인코딩 → checksum 다름 | `Ked-Import`(`-InFile`) (F45) |
| PowerShell 이 보낸 한글이 깨짐 / 본문 없는 POST 가 415 | PS 5.1 이 문자열 본문을 재인코딩 · form Content-Type 을 몰래 붙임 | 수동 테스트 라벨은 ASCII, 415 는 PS 탓(Unity 는 400) (F47) |
| 응답 한글이 `ì..` 로 깨짐 | PS 5.1 이 charset 없는 응답을 ISO-8859-1 로 읽음 | `api.ps1` 이 바이트를 UTF-8 로 읽는다 (M3) |
| DB 질의 결과에 같은 seq 가 스무 번 | 필터 없는 SELECT — seed·지난 검증 행이 섞임 | **회차·유저로 거른다** (M7-check §1-A) |
| `@Sql` seed 의 한글이 `?꽦?떎` | `@SqlConfig` encoding 기본값 = 플랫폼(MS949) | `encoding = "UTF-8"` (F35) |
| 시각이 9시간 어긋남 | DATETIME 쓰기 변환 없음 | 쓰기는 `UtcTime.toDbValue`, 읽기는 `OffsetDateTime`, URL 에 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` (D-009) |
| Workbench `DELETE` 가 1175 로 막힘 | safe update mode(클라이언트 설정) | `SET SQL_SAFE_UPDATES = 0;` — 세션에만, 되돌리지 않는다 (F22·F34) |
| 회차 POST 가 400 `MALFORMED_JSON` | F6 전 클라(본문 없음) | 의도한 단절 — 클라를 F6 이후로 (D-019) |
| 응답에 `"forkedFrom": null` | 키는 있고 값이 null — 정상 | `== null` 로 분기. "키가 빠진다"는 옛 오기 (F46) |

## 8. 자주 하는 작업 — 순서만

**새 마이그레이션**: `src/main/resources/db/migration/V{n}__snake_desc.sql` 추가 → `DbCleaner`·`db/seed.sql` 의 DELETE 목록(자식 → 부모) 갱신 → `cleanTest test`(game_test 에 자동 적용) → `bootRun`(game). FK 를 지탱하는 인덱스는 단독 DROP 불가(F39) — 한 문장 ALTER 로.

**새 통계 숫자**: 정의부터 한 줄로 적는다(무엇을 세나, 분모는) → `sql/stats/*.sql` (머리 주석에 정의·seed 기대값) → record → `StatsRepository/Service/Controller` → **seed 설계표로 손 검산한 기대값을 먼저 적고** 테스트 → 화면은 그리기만.

**새 엔드포인트**: 경계에서 한 번 검증(400) → 존재(404) → 소유(403 — `/users/{id}/**` 는 인터셉터가, `/playthroughs/**` 는 서비스가) → 쓰기. 에러는 `{code, message}` 로만. 컨트롤러는 상태 코드 결정만.

**검증 문서 쓰기**(`M{n}-check.md`): 머리에 선행 조건과 함정 → 절마다 "조작 → 기대(정확한 값)" 표 → 결과 표 → 커밋 메시지. 기대값은 코드 전에 적는다.

**커밋**: `feat: …`(구현) 여러 개 + `test(M{n}): 테스트 및 문서 갱신`(마감) 하나. 커밋은 사람이.

**M 하나를 시작할 때**: `STATUS.md` 지금 → `plans/M{n}.md` §3 사고흐름 → 작업 표 순서대로 → 끝나면 STATUS·plans·DECISIONS 갱신 → 전체 계획서 재점검.

## 9. 어디를 보나 (30초 버전)

| 궁금한 것 | 파일 |
|---|---|
| 지금 어디까지 왔나 | `STATUS.md` 머리 두 줄 |
| 왜 이렇게 했나 | `DECISIONS.md` (D-번호로 검색) |
| 서버가 실제로 이렇게 동작하나 | `M{n}-check.md` §결과 표, `RETRO.md` §2 |
| 클라와의 계약 | `handoff/server-2026-09-02.md` (정본), `README.md` API 표 |
| 이 숫자의 정의 | `src/main/resources/sql/stats/*.sql` 머리 주석 |
| 이 명령이 정확히 무엇을 하나 / 이 보장은 어느 테스트가 지키나 | `REFERENCE.md` |
| 무엇을 배웠나 | `RETRO.md` |
