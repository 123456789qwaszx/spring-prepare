# M6 검증 절차 — 마감

> PLAN §2.6. 결과는 §5 표에. 선행: M5 `검증됨`, D-011·D-012·D-013.
> **이번 M은 DB 를 통째로 다시 만들고**(M6-1b), **기존 API 호환이 깨진다** (토큰·관리자 키).
> M0~M5 의 check 문서를 다시 돌릴 때는 각 문서 머리의 "M6 이후" 주석을 먼저 읽는다.

창은 **Workbench** 와 **터미널 ①**(gradle/bootRun), **터미널 ②**(API 호출) 셋이다.

---

## 0. 사전 준비 — 프로퍼티와 옛 마이그레이션 폴더

**(a) `src/main/resources/application-local.properties`** (gitignore) 에 한 줄 추가:

```properties
app.admin-key=<아무 비밀 문자열>
```

없으면 **기동 자체가 실패한다** — 조용히 무인증으로 뜨는 것보다 시끄럽게 죽는 쪽을 골랐다
(application.properties 주석). 커밋되는 파일에는 기본값을 두지 않는다.

**(b) `src/test/resources/application-test.properties`** (gitignore) 에 두 줄 추가 —
`.example` 파일의 "M6 추가분" 블록을 그대로 복사하면 된다:

```properties
app.admin-key=test-admin-key
app.auth.bcrypt-strength=4
```

**(c) 옛 마이그레이션 폴더 삭제.** 파일이 `src/main/resources/db/migration/`(클래스패스)으로
옮겨졌다 (D-012). 루트의 옛 폴더는 git 으로 지운다 — 둘이 남아 있으면 어느 쪽이 진짜인지 매번 헷갈린다:

```powershell
git rm db/migrations/V2__gamedef_checksum.sql db/migrations/V3__stats_indexes.sql
```

`db/seed.sql` 은 마이그레이션이 아니므로 그대로 `db/` 에 남는다.

**(d) 서버가 떠 있으면 죽인다** (M4-check §2 와 같은 이유):

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

> **정본 관계 (M6-10, D-012)** — 같은 DDL 을 담은 파일이 둘이 됐다:
> `docs/schema.sql` 은 **읽기용 정본**(사람이 열어 보는 것), `V1__init.sql` 은 **적용본**(Flyway 가 실행하는 것).
> 스키마를 바꿀 일이 생기면 **둘 다 고치는 것이 아니라** 새 `V{n}` 마이그레이션을 더한다 —
> V1 은 이미 적용된 과거라 고칠 수 없다. schema.sql 은 v1 스냅샷으로 남는다.

---

## 1. 재생성 (M6-1b) — **Workbench → 터미널 ①**

> ⚠ `game` 과 `game_test` 의 **모든 데이터가 사라진다.** M5 까지의 수동 확인 결과는
> 각 check 문서에 기록돼 있고, 데이터는 `seed.sql` 이 복구한다 — 이 비용이 거의 0 인 것이
> baseline 대신 재생성을 고른 이유다 (D-012).

### 1.1 DROP → CREATE — **Workbench**

```sql
DROP DATABASE IF EXISTS game;
DROP DATABASE IF EXISTS game_test;
CREATE DATABASE game      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE game_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

> CHARACTER SET 지정은 원래 schema.sql 머리의 `CREATE DATABASE` 줄이 하던 일이다.
> 그 줄을 V1 에서 뺐으므로 (D-012 갱신 — `USE game` 이 남으면 game_test 마이그레이션이
> game 으로 갈아탄다) DB 생성은 여기서 사람이 한다.

### 1.2 기동 = 마이그레이션 — **터미널 ①**

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare
.\gradlew.bat bootRun
```

기동 로그에 Flyway 가 지나간다. `Migrating schema \`game\` to version "1 - init"` … `"5 - sessions"`.

### 1.3 이력 확인 — **Workbench**

```sql
SELECT version, description, success FROM game.flyway_schema_history ORDER BY installed_rank;
```

| version | description | success |
|---|---|---|
| 1 | init | 1 |
| 2 | gamedef checksum | 1 |
| 3 | stats indexes | 1 |
| 4 | event once per playthrough | 1 |
| 5 | sessions | 1 |

**이 표가 "어디까지 적용했나를 사람이 기억하는 상태"의 끝이다** — M0(schema.sql 두 번) →
M1(R4) → M5(V3 두 번)가 쌓아 온 근거의 해소.

V4 가 실제로 일했는지도 본다:

```sql
SHOW INDEX FROM game.event_log WHERE Key_name = 'uk_event_once';
-- 2행: (playthrough_id, event_key). 4행(옛 정의)이면 V4 가 안 돈 것이다.
```

### 1.4 `game_test` — 첫 테스트 실행이 자동으로 한다

`game_test` 는 손대지 않는다. §2 의 테스트가 컨텍스트를 띄우는 순간 Flyway 가 같은
마이그레이션을 적용한다 — **"양쪽 자동 적용"(D-012 의 (a))이 실제로 이렇게 생겼다.**
§2 를 돌린 뒤 `game_test.flyway_schema_history` 를 §1.3 과 같은 쿼리로 확인한다.
**두 DB 가 같은 경로로 만들어졌다** — 그것이 드리프트가 없다는 뜻이다.

### 1.5 seed — **Workbench** (`game` 에)

M5-check §1 과 같은 절차: `game` 더블클릭(굵게) → `db/seed.sql` 열기 → **⚡⚡**.

```sql
SELECT username, LEFT(password, 7) AS pw_head FROM users;
-- 5행 전부 pw_head = '$2a$10$'. 'seed-on' 이 보이면 옛 seed 다 (M6-3b 미반영).
SELECT COUNT(*) FROM event_log;   -- 27 (V4 새 UNIQUE 와 호환 — 중복 없음은 2026-08-30 확인)
```

---

## 2. 자동 테스트 — **터미널 ①**

```powershell
.\gradlew.bat cleanTest test
```

기대: **96건** = M0 7(+1) + M1 25(+2) + M2 20(+2) + M3 13 + M4 9 + M5 10(+2) + **M6 11**(Auth 7 + ErrorFormat 4) + `contextLoads` 1.
늘어난 (+) 는 전부 보호 자체의 테스트다 — 401·403·키·형식. 기존 테스트 중 셋은 의미가 바뀌었다:
"없는 id → 404" 류가 "남의 것 → 403" 이 됐다 (인터셉터가 서비스보다 먼저 끊는다 — plans/M6.md §5).
개수보다 중요한 것: **작업 요약 줄이 `executed`** 인지 (F21), 첫 실행에서 `game_test` 에
Flyway 로그가 지나갔는지.

| 증상 | 원인 | 대응 |
|---|---|---|
| `Could not resolve placeholder 'app.admin-key'` | §0(b) 누락 | application-test.properties 에 추가 |
| `Found non-empty schema ... without schema history table` | game_test 를 DROP 안 함 | §1.1 — **baseline-on-migrate 를 켜지 않는다** (C1) |
| 로그인 테스트가 401 | 픽스처가 옛 평문 | Fixtures/seed 의 BCrypt 해시 반영 확인 |
| 전부 401 | 인터셉터 경로에 걸렸는데 토큰 없음 | AuthSupport.login 이 setUp 에 있는지 (C6) |
| BCrypt 로 느려짐 | strength 10 으로 돎 | §0(b) 의 `app.auth.bcrypt-strength=4` (C7) |

---

## 3. 수동 시나리오 — **터미널 ②** (서버는 터미널 ① 에서 bootRun 중)

### 3.0 준비 — 통째로 붙여넣는다

M4-check 의 Call-Api 에 **헤더 파라미터가 추가**됐다 — M6 의 주인공이 헤더다.

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare

function Call-Api {
    param($Method, $Uri, $Body, $Headers)
    try {
        $args = @{ Method = $Method; Uri = $Uri; UseBasicParsing = $true }
        if ($Body)    { $args.ContentType = 'application/json'; $args.Body = $Body }
        if ($Headers) { $args.Headers = $Headers }
        $r = Invoke-WebRequest @args
        "{0}`n{1}" -f [int]$r.StatusCode, [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        # HTTP 응답이 아예 없는 예외(예: 헤더 값에 비ASCII → 전송 전 실패)는 그대로 던져서 원문을 보이게 한다.
        # 실측(2026-08-31): 자리표시자 '<…의 값>' 이 헤더에 들어가자 .Response 가 null 이라 여기서 이중으로 넘어졌다.
        if (-not $resp) { throw }
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd(); $sr.Close()
        "{0}`n{1}" -f [int]$resp.StatusCode, $text
    }
}

$BASE = "http://localhost:8080"
$ADMIN = @{ 'X-Admin-Key' = '<application-local.properties 의 값>' }
$SNAP = '{"nodeName":"qwer_EP01","variables":{"$int":1}}'
```

### 3.1 회원가입 → 평문 없음

```powershell
Call-Api POST "$BASE/users" '{"username":"m6","password":"secret-pw"}'
# 기대: 201, {"id":6,...} (seed 5명 뒤라 6)
```

**Workbench** 에서 완료 기준을 직접 본다:

```sql
SELECT username, password FROM game.users WHERE username = 'm6';
-- password 가 $2a$10$... 60자. 'secret-pw' 가 보이면 M6-3 이 안 붙은 것이다.
```

### 3.2 로그인 — 성공과, 구분되지 않는 실패

```powershell
$login = Invoke-WebRequest -Method POST -Uri "$BASE/auth/login" -ContentType 'application/json' -Body '{"username":"m6","password":"secret-pw"}' -UseBasicParsing
$TOKEN = ($login.Content | ConvertFrom-Json).token
$USER_ID = ($login.Content | ConvertFrom-Json).userId
$AUTH = @{ Authorization = "Bearer $TOKEN" }

Call-Api POST "$BASE/auth/login" '{"username":"m6","password":"wrong"}'
Call-Api POST "$BASE/auth/login" '{"username":"ghost","password":"wrong"}'
# 기대: 둘 다 401 이고 본문이 **한 글자까지 같다** — 응답으로 계정 존재를 조회할 수 없다.
```

### 3.3 토큰 없이 → 401, 토큰으로 → 정상 (완료 기준 ①의 절반)

```powershell
Call-Api GET  "$BASE/users/$USER_ID/playthroughs"
# 기대: 401 {"code":"UNAUTHORIZED",...}

$p = Invoke-WebRequest -Method POST -Uri "$BASE/users/$USER_ID/playthroughs" -Headers $AUTH -UseBasicParsing
$PT = ($p.Content | ConvertFrom-Json).playthroughId
"PT=$PT"

Call-Api PUT "$BASE/playthroughs/$PT/saves/1" "{""chapterId"":""qwer"",""chapterVersion"":1,""currentEpisodeId"":""EP01"",""snapshot"":$SNAP,""playSeconds"":10,""deviceKey"":""device-m6"",""baseRevision"":0}" $AUTH
# 기대: 200, revision 1
```

### 3.4 남의 회차 → 403 (완료 기준 ①의 나머지 절반)

seed 의 amiya(비밀번호 `seed-only`, 회차 1~4)로 시험한다:

```powershell
Call-Api POST "$BASE/playthroughs/1/end" $null $AUTH
# 기대: 403 {"code":"FORBIDDEN",...} — 회차 1 은 amiya 의 것이다. m6 토큰으로는 못 끝낸다.

Call-Api GET "$BASE/users/1/summary" $null $AUTH
# 기대: 403 — /users/{id}/** 는 본인만 (인터셉터가 경로 id 로 끊는다).
```

### 3.5 관리자 키 (M6-7, D-013)

> ⚠ 파일은 **바이트 그대로**(`-InFile`) 보낸다 — M1-check 가 이미 세운 규칙인데 이 문서의 초안이
> `Get-Content -Raw` 로 되돌렸다가 검증에서 잡혔다(§6). 문자열로 읽으면 PS 5.1 이 재인코딩하며
> 한글 바이트를 깨뜨리고, 깨진 정도에 따라 checksum 이 어긋나거나(M1) JSON 자체가 부서진다(이번, §6).

```powershell
$FIXTURE_PATH = "src\test\resources\content\qwer-events.progression.json"

Call-Api POST "$BASE/content/chapters" "x"
# 기대: 401 — 키 없음. (M0~M5 시절에는 누구나 올릴 수 있었다. 본문은 검사 전에 키에서 끊기므로 아무거나.)

$r = Invoke-WebRequest -Method POST -Uri "$BASE/content/chapters" -ContentType 'application/json' -InFile $FIXTURE_PATH -Headers $ADMIN -UseBasicParsing
"$([int]$r.StatusCode)`n$([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()))"
# 기대: 201, {"chapterId":"qwer-events","version":1,"episodeCount":8} (재실행이면 200 — 재수입 멱등)

Call-Api GET "$BASE/content/chapters"
# 기대: 200 — **GET 은 키 없이 공개다.** 클라가 콘텐츠를 내려받는 경로다 (C5).

Call-Api GET "$BASE/stats/events"
# 기대: 401 — 집계는 관리자용 (D-013).
Call-Api GET "$BASE/stats/events" $null $ADMIN
# 기대: 200. reached 수는 seed 그대로 15 / 8 / 4.
# ⚠ 비율(reachRate)은 검증 **순서**에 따라 다르다 — 분모가 "전체 회차"라서 §3.3 에서 만든
#   회차가 이미 세어진다 (예: 21회차면 71.4/38.1/19.0). 분모가 는 것이지 집계가 틀린 게 아니다.
#   §3.8 까지 끝나면 ENDING_A 의 reached 도 8→9 가 된다 — 같은 이유다.
```

### 3.6 에러 형식 (M6-8) — F20 의 마감

```powershell
Call-Api POST "$BASE/users" '{"username": "broken'
# 기대: 400 {"code":"MALFORMED_JSON",...} — M2 에서는 Spring 기본 형식({timestamp,...})이 나갔다.

Call-Api GET "$BASE/no-such-path"
# 기대: 404 {"code":"NOT_FOUND",...} — 포괄 핸들러가 상태 코드를 **보존**하며 형식만 바꾼다.
```

### 3.7 로그아웃 → 즉시 무효

```powershell
Call-Api POST "$BASE/auth/logout" $null $AUTH        # 기대: 204
Call-Api GET  "$BASE/users/$USER_ID/playthroughs" $null $AUTH
# 기대: 401 — 행이 지워졌으니 그 즉시 죽는다. (재시험하려면 §3.2 로 다시 로그인.)
```

### 3.8 이벤트 중복 흡수 (M6-2b, D-011 의 파생 효과 실물)

같은 회차의 **다른 슬롯**에서 같은 EventKey 에 다시 도달해 본다 — V4 이전이라면
콘텐츠 버전이 같을 때만 409 였고, V4 이후라면 무조건 중복인데, **409 가 아니라 흡수**돼야 한다:

```powershell
# §3.2 로 재로그인해 $AUTH 갱신 후. qwer-events v1 의 EP04_01(=ENDING_A) 을 슬롯 2, 3 에서:
Call-Api PUT "$BASE/playthroughs/$PT/saves/2" "{""chapterId"":""qwer-events"",""chapterVersion"":1,""currentEpisodeId"":""EP04_01"",""snapshot"":$SNAP,""playSeconds"":60,""deviceKey"":""device-m6"",""baseRevision"":0,""events"":[{""episodeId"":""EP04_01"",""occurredAt"":""2026-08-30T12:00:00Z""}]}" $AUTH
# 기대: 200, acceptedEvents 1 — 처음이다.

Call-Api PUT "$BASE/playthroughs/$PT/saves/3" "{""chapterId"":""qwer-events"",""chapterVersion"":1,""currentEpisodeId"":""EP04_01"",""snapshot"":$SNAP,""playSeconds"":90,""deviceKey"":""device-m6"",""baseRevision"":0,""events"":[{""episodeId"":""EP04_01"",""occurredAt"":""2026-08-30T12:05:00Z""}]}" $AUTH
# 기대: 200, **acceptedEvents 0** — 409 가 아니다. "이미 있으면 빼고 넣기"가 일했다.
```

```sql
-- Workbench: ENDING_A 는 이 회차에 한 행뿐이다.
-- <PT> 는 꺾쇠까지 지우고 §3.3 이 출력한 숫자로 바꾼다 (예: … playthrough_id = 21 …).
SELECT COUNT(*) FROM game.event_log WHERE playthrough_id = <PT> AND event_key = 'ENDING_A';  -- 1
```

---

## 4. M0~M5 check 문서와의 관계

각 문서 머리에 "M6 이후: 토큰 필요" 주석이 붙었다. 옛 시나리오를 다시 돌릴 일이 있으면
§3.0 의 helper 로 로그인해 `$AUTH` 를 만들고, 각 Call-Api 호출에 넷째 인자로 넘긴다.
콘텐츠 POST 는 `$ADMIN`. **문서 본문의 명령은 그 M 시점의 기록이라 고치지 않았다.**

---

## 5. 결과 기록

**전부 통과 — 2026-08-31 새벽 (아미야).**

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §1.3 flyway_schema_history | V1~V5, success 전부 1 | ✅ | |
| §1.3 uk_event_once | (playthrough_id, event_key) 2행 | ✅ | V4 의 한 문장 ALTER 가 FK 를 안 끊고 통과 |
| §1.4 game_test 이력 | game 과 동일 | ✅ | 첫 `cleanTest test` 가 자동 적용 (F42) |
| §1.5 seed | pw_head 전부 `$2a$10$`, 이벤트 27 | ✅ | |
| §2 자동 테스트 | 96건, `executed` | ✅ **96/96** | 첫 실행 통과 — 무컴파일 작성 M0~M6 연속 |
| §3.1 평문 없음 | `$2a$10$…` | ✅ | m6 의 해시 60자 확인 |
| §3.2 로그인/실패 동일 본문 | 200 / 401·401 같은 본문 | ✅ | 한 글자까지 동일 |
| §3.3 401 → 200 | 토큰이 가른다 | ✅ | PT=21, revision 1 |
| §3.4 남의 회차·남의 summary | 403 / 403 | ✅ | 서비스 403 / 인터셉터 403 — 메시지로 층이 구분된다 |
| §3.5 관리자 키 | 401 → 201, GET 공개, stats 401→200 | ✅ | 201 은 `-InFile` 로 (§6-2). stats 비율은 분모 21 로 71.4/38.1/19.0 — 정의대로 (§3.5 주석) |
| §3.6 에러 형식 | MALFORMED_JSON, NOT_FOUND | ✅ | |
| §3.7 로그아웃 | 204 → 401 | ✅ | "유효하지 않은 토큰" — 행 삭제 즉시 무효 |
| §3.8 이벤트 흡수 | acceptedEvents 1 → 0, 행 1개 | ✅ | 409 없이 흡수. SQL COUNT = 1 |

## 6. 부수적으로 확인된 것

- **이 문서의 초안 결함 둘이 검증에서 잡혔다** — M3 의 "문서의 오류가 실행으로 잡혔다"(§5.1 기대표 정정)와 같은 계보다.
  - **(1) §3.5 가 `Get-Content -Raw` 로 파일을 보내게 했다.** M1-check 가 이미 "`-InFile` 을 쓴다"고 세운 규칙의 위반이다. PS 5.1 이 BOM 없는 UTF-8 을 잘못 읽고 재인코딩해 한글 바이트를 깨뜨렸고, 이번엔 checksum 어긋남(M1 의 우려) 정도가 아니라 **JSON 구조 자체가 부서져** 서버의 `readTree` 가 JacksonException 을 던졌다. → §3.5 를 `-InFile` 로 정정.
  - **(2) stats 기대값이 "seed 직후"를 가정했다.** 분모가 전체 회차라 §3.3 이 만든 회차가 세어져 75.0 이 아니라 71.4 가 나온다 — 집계는 정의대로 맞다. → 기대를 reached 수(15/8/4)로 바꾸고 주석 추가.
- **위 (1) 이 뜻밖의 것을 실증했다**: 서비스 층 `readTree` 의 JacksonException 은 핸들러 목록에 없어 **포괄 핸들러의 500 `INTERNAL_ERROR`** 로 나간다. 형식(code·message)은 지켰다 — M6-8 이 일한 장면 — 그러나 원인은 클라의 깨진 본문이라 **의미상 400 `MALFORMED_JSON` 이 맞다.** 컨버터 층(@RequestBody 객체 바인딩)은 잡히는데, byte[] 로 받아 서비스에서 파싱하는 콘텐츠 수입 경로만 그물 밖이다. → plans/M6.md §9 잔손질로 기록 (F44).
- **PS 5.1 은 헤더 값에 비ASCII 가 있으면 요청을 보내기 전에 예외를 던지고, 그 예외에는 `.Response` 가 없다.** 자리표시자 문자열('<…의 값>')이 그대로 헤더에 들어가며 발견됐다. Call-Api 의 catch 가 여기서 이중으로 넘어졌다 → §3.0 에 null 가드 추가. **서버는 그동안 한 번도 틀린 말을 하지 않았다** — 두 번의 "실패"(자리표시자, -Raw) 모두 클라이언트 쪽이었고, M3 의 PowerShell 한글 깨짐과 같은 교훈이다: 이상하면 읽는(보내는) 쪽부터 의심한다. Unity 의 UnityWebRequest 는 바이트를 그대로 다루므로 이 두 함정 모두 해당 없다 (M7 선행 조건 참조).
