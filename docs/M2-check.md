# M2 검증 절차 — 회차·세이브 업로드/복구

> **M6 이후**: 이 문서의 명령은 M2 시점 기준이라 **토큰 없이** 적혀 있다. 지금 다시 돌리려면
> `/users`(POST 제외)·`/playthroughs` 는 `Authorization: Bearer <토큰>`, `/content` POST 는 `X-Admin-Key`,
> `/stats` 는 `X-Admin-Key` 가 필요하다. 로그인과 헤더 helper 는 **M6-check §3.0** 을 쓴다.

> PLAN §2.6. 결과는 §6 표에. 선행: M1 `검증됨`, 결정 D-008 반영됨.
> **이번 M은 스키마 변경이 없다** — 마이그레이션 단계가 없다.

---

## 0. 선행 조건 확인

M1에서 "선행 조건 확인은 실패가 조용하다"는 교훈을 얻었으므로(ANALYSIS §4.1), 확인 쿼리와 기대 출력을 함께 적는다.

```sql
SELECT COUNT(*) AS tables_in_game      FROM information_schema.tables WHERE table_schema = 'game';
SELECT COUNT(*) AS tables_in_game_test FROM information_schema.tables WHERE table_schema = 'game_test';
```
둘 다 **9** 여야 한다.

```sql
SELECT COUNT(*) AS qwer_versions FROM game.chapter_contents WHERE chapter_id = 'qwer';
```
**1 이상.** M1의 수동 확인에서 v1·v2를 넣었다면 2다. 0이면 §3.1을 하기 전에 M1-check §3.1로 챕터를 하나 수입해 둔다 — 세이브는 특정 챕터 **버전**을 가리켜야 하기 때문이다.

```sql
SELECT id, username FROM game.users;
```
사용자가 하나는 있어야 한다 (M0에서 만든 alice/bob). id를 적어 둔다 — 아래에서 `$USER_ID`로 쓴다.

---

## 1. 컴파일과 자동 테스트

```powershell
.\gradlew.bat compileTestJava
.\gradlew.bat cleanTest test
```

기대: **48건** (M0 6 + M1 23 + M2 18 + `contextLoads` 1).
M2 는 `PlaythroughApiTest` 6 + `SaveSlotApiTest` 12 다. `DbCleaner` 가 9개 테이블로 바뀌었으므로 M0·M1 까지 함께 돌린다.

> **`cleanTest` 를 반드시 붙인다.** 그냥 `gradlew test` 는 입력이 안 바뀌었다고 판단하면 조용히 건너뛰고도
> `BUILD SUCCESSFUL` 을 낸다:
> ```
> 5 actionable tasks: 5 up-to-date            ← 아무것도 안 돌았다
> 6 actionable tasks: 2 executed, 4 up-to-date ← 실제로 돌았다
> ```
> **초록색이 아니라 작업 요약 줄을 읽는다.** 테스트 이름이 한 줄도 안 찍히면 실행되지 않은 것이다
> (`build.gradle` 의 `testLogging` 이 켜져 있어 실제로 돌면 전부 찍힌다).

| 메시지 | 원인 |
|---|---|
| `Cannot delete or update a parent row` | `DbCleaner` 순서 문제. 자식이 부모보다 앞인지 확인 |
| `snapshot` 단언 실패, 값이 `"{\"...\"}"` | `@JsonRawValue` 가 안 먹었다 (M2.md C10) |
| `revision` 이 0 | INSERT 값에 1이 없다 (C11) |

---

## 2. 서버 실행

```powershell
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "사용 중" } else { "비어 있음" }
.\gradlew.bat bootRun
```

M1 때 띄운 서버가 남아 있으면 **옛 코드**라 `/playthroughs/**` 가 404다. 반드시 죽이고 새로 띄운다.

> `-ErrorAction SilentlyContinue` 를 붙이는 이유: `Get-NetTCPConnection` 은 일치하는 것이 없으면 빈 결과가 아니라 **오류**를 던진다.
> "일치하는 MSFT_NetTCPConnection 개체를 찾지 못했습니다" 는 실패가 아니라 **포트가 비어 있다는 뜻**이다.
> 살아 있으면 `Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`.

---

## 3. API 시나리오 (PowerShell 5.1)

터미널 ②에서. `Call-Api` 헬퍼를 다시 등록한다 (4xx 본문을 보려면 필요하다).

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare

function Call-Api {
    param($Method, $Uri, $Body)
    try {
        $r = if ($Body) { Invoke-WebRequest -Method $Method -Uri $Uri -ContentType 'application/json' -Body $Body -UseBasicParsing }
             else       { Invoke-WebRequest -Method $Method -Uri $Uri -UseBasicParsing }
        "{0}`n{1}" -f [int]$r.StatusCode, $r.Content
    } catch {
        # PS 5.1 은 4xx/5xx 에서 예외를 던지고, $_.ErrorDetails.Message 가 비어 있는 경우가 있다.
        # 그러면 응답 스트림을 직접 읽는다 — 안 그러면 {"code":"..."} 본문을 못 본다.
        $resp = $_.Exception.Response
        $text = $_.ErrorDetails.Message
        if (-not $text -and $resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $sr.ReadToEnd()
            $sr.Close()
        }
        "{0}`n{1}" -f [int]$resp.StatusCode, $text
    }
}

$USER_ID = 1     # §0 에서 확인한 값
$SNAP = '{"nodeName":"qwer_EP02_01","lineId":"line:0007","variables":{"$int":5},"StageState":{"slots":["c1"]},"ProgressionState":{"CurrentEpisodeId":"EP02_01"}}'

# 요청 본문을 미리 다 만들어 둔다 — 창을 새로 열면 이 블록만 다시 붙여넣으면 된다
$body1 = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":10,`"deviceKey`":`"device-A`"}"
$body2 = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP02_01`",`"snapshot`":$SNAP,`"playSeconds`":25,`"deviceKey`":`"device-A`"}"
$bad   = "{`"chapterId`":`"qwer`",`"chapterVersion`":99,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":0,`"deviceKey`":`"device-A`"}"
$bodyB = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":5,`"deviceKey`":`"device-B`"}"
$noDev = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":5}"

# 확인 — $body1 이 온전한 JSON 인지 눈으로 본다
"USER_ID=$USER_ID"
$body1
```

> **변수는 창과 함께 죽는다.** PowerShell 창을 새로 열면 위 블록 전체를 다시 붙여넣어야 한다.
> `$SNAP` 이 비면 `"snapshot":,` 같은 **깨진 JSON** 이 만들어지고, 서버는 본문 파싱 단계에서 400 을 낸다.
>
> **응답 모양이 죽은 자리를 알려준다:**
> - `{"code":"NOT_FOUND","message":"..."}` → 우리 `GlobalExceptionHandler`. 컨트롤러·서비스까지 **도달했다**
> - `{"timestamp":"...","status":400,"error":"Bad Request","path":"..."}` → Spring 기본. 컨트롤러에 **닿기도 전에** 죽었다 (대개 본문 파싱 실패)
>
> 두 번째 형식이 보이면 서버 코드가 아니라 **보낸 요청**을 먼저 의심한다.

### 3.1 회차 만들기

```powershell
Call-Api POST "http://localhost:8080/users/$USER_ID/playthroughs"
```
기대: `201` / `{"playthroughId":1}`

**받은 번호를 `$PT` 에 넣고 눈으로 확인한다. 이 줄을 건너뛰면 이후 요청이 전부 404 가 된다** —
PowerShell 은 없는 변수를 빈 문자열로 펼치므로 `playthroughs//saves/1` 같은 경로가 만들어지고, 그건 어떤 매핑에도 맞지 않는다.

```powershell
$PT = 1                                          # 위 응답의 playthroughId
"확인: http://localhost:8080/playthroughs/$PT/saves/1"
# playthroughs/1/saves/1  → 정상
# playthroughs//saves/1   → $PT 가 비어 있다
```

> **`$PID` 를 쓰지 않는 이유**: PowerShell 의 자동 변수(현재 프로세스 ID)라 읽기 전용이다. 대입하면 `Cannot overwrite variable PID because it is read-only or constant` 가 난다. 셸이 이미 쓰는 이름을 피하는 것도 규칙 중 하나다.

없는 사용자로도 해 본다.

```powershell
Call-Api POST 'http://localhost:8080/users/999999/playthroughs'
```
기대: `404 NOT_FOUND`. FK 위반(400)이 아니다 — 서비스가 먼저 조회하기 때문이다.

### 3.2 세이브 업로드 — revision 이 1, 2로 오른다

```powershell
$body1 = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":10,`"deviceKey`":`"device-A`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/1" $body1
```
기대: `200` / `{"revision":1,"updatedAt":"..."}`

같은 슬롯에 한 번 더:

```powershell
$body2 = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP02_01`",`"snapshot`":$SNAP,`"playSeconds`":25,`"deviceKey`":`"device-A`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/1" $body2
```
기대: `200` / `revision: 2`

**슬롯은 여전히 하나다** — upsert 이지 append 가 아니다.

```sql
SELECT COUNT(*) FROM game.save_slots;   -- 1
```

첫 업로드가 `revision: 0` 이면 INSERT 값에 1이 없는 것이다. `ON DUPLICATE KEY UPDATE` 절은 **신규 INSERT 때 실행되지 않는다**는 것이 M2의 함정이다.

### 3.3 없는 콘텐츠 버전 → 404

```powershell
$bad = "{`"chapterId`":`"qwer`",`"chapterVersion`":99,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":0,`"deviceKey`":`"device-A`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/2" $bad
```
기대: `404 NOT_FOUND`.

서비스가 먼저 `chapter_contents` 를 조회하지 않았다면 FK 위반이 나서 `400 CONSTRAINT_VIOLATION` 이 됐을 것이다. **어느 쪽도 500은 아니지만, 클라에게 맞는 답은 "그 콘텐츠 버전이 서버에 없다"** 이므로 404다.

### 3.4 스냅샷 왕복 — 서버는 열지 않는다

```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves/1"
```

기대: `snapshot` 이 **객체로** 나온다.

```json
{"slotNo":1,"chapterId":"qwer","chapterVersion":1,"currentEpisodeId":"EP02_01",
 "revision":2,"playSeconds":25,"updatedAt":"...","device":"device-A",
 "snapshot":{"nodeName":"qwer_EP02_01","lineId":"line:0007", ...}}
```

`"snapshot":"{\"nodeName\":...}"` 처럼 **따옴표로 감싸진 문자열**로 나오면 `@JsonRawValue` 가 안 먹은 것이다.

목록에는 스냅샷이 없어야 한다.

```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves"
```
기대: `snapshot` 키 자체가 없다. 목록 쿼리가 그 컬럼을 SELECT 하지 않기 때문이다.

### 3.5 기기 — 두 종류, 그리고 없는 경우

```powershell
$bodyB = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":5,`"deviceKey`":`"device-B`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/2" $bodyB

$noDev = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":5}"
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/3" $noDev

Call-Api GET "http://localhost:8080/playthroughs/$PT/saves"
```

기대: 슬롯 셋. 1·2는 `device` 가 `device-A`/`device-B`, 3은 `device` 키가 없다(null).
**3번 슬롯이 목록에서 사라지면 안 된다** — `devices` 를 LEFT JOIN 한 이유다.

```sql
SELECT id, user_id, device_key, last_seen_at FROM game.devices;   -- 2행
```

### 3.6 슬롯 번호 — D-008

범위 밖은 400:

```powershell
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/0"   $body1
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/128" $body1
```
기대: 둘 다 `400 BAD_REQUEST`. TINYINT 오버플로 500이 아니다.

**개수 상한은 없다:**

```powershell
foreach ($n in 5, 42, 127) {
    Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/$n" $body1
}
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves"
```
기대: 전부 `200`, 목록에 슬롯 6개(1,2,3,5,42,127). 예전 계획의 "슬롯 4 → 400"을 대체하는 기준이다.

### 3.7 회차 목록과 종료

```powershell
Call-Api GET "http://localhost:8080/users/$USER_ID/playthroughs"
```
기대: `slotCount: 6`, `endedAt` 키 없음(진행 중).

```powershell
Call-Api POST "http://localhost:8080/playthroughs/$PT/end"
Call-Api POST "http://localhost:8080/playthroughs/$PT/end"
```
기대: **두 응답이 완전히 같다.** `endedAt` 이 덮이지 않는다 — `UPDATE ... WHERE ended_at IS NULL` 이 만든 멱등성이다.

---

## 4. Workbench 로 직접 보기

```sql
USE game;

-- 슬롯 전체. chapter_content_id 는 "어느 버전의 챕터인가"를 가리킨다
SELECT s.id, s.playthrough_id, s.slot_no, s.chapter_content_id,
       c.chapter_id, c.version, s.current_episode_id,
       s.revision, s.play_seconds, s.device_id, s.updated_at
FROM save_slots s
JOIN chapter_contents c ON c.id = s.chapter_content_id
ORDER BY s.slot_no;

-- 스냅샷은 JSON 이다 — 서버는 열지 않았지만 DB 는 파싱해서 들고 있다
SELECT slot_no,
       JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.nodeName')) AS node_name,
       JSON_LENGTH(snapshot) AS top_level_keys,
       LENGTH(snapshot) AS stored_bytes
FROM save_slots ORDER BY slot_no;

-- 기기
SELECT * FROM devices;

-- 회차 요약을 SQL 로 직접 (앱의 목록 쿼리와 같은 모양)
SELECT p.id, p.started_at, p.ended_at,
       (SELECT COUNT(*) FROM save_slots s WHERE s.playthrough_id = p.id) AS slot_count
FROM playthroughs p ORDER BY p.id;
```

`JSON_EXTRACT` 로 스냅샷 안을 들여다볼 수 있다는 점이 흥미롭다 — **DB 는 볼 수 있지만 서버 코드는 보지 않는다.** 이것이 "해석하지 않는다"가 기술적 불가능이 아니라 **지켜야 할 규율**이라는 뜻이다.

---

## 5. 정리

```powershell
# 터미널 ① 에서 Ctrl+C  (Terminate batch job (Y/N)? → Y)
# bootRun 은 자식 JVM 이 살아남는 경우가 있으므로 확인까지가 한 세트다
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "아직 살아 있음" } else { "종료됨" }
```

```sql
-- 수동 확인 데이터 정리 (자식 → 부모). DbCleaner 와 같은 순서다.
SET SQL_SAFE_UPDATES = 0;

DELETE FROM game.save_slots;
DELETE FROM game.playthroughs;
DELETE FROM game.devices;

SET SQL_SAFE_UPDATES = 1;
```

> **`Error Code: 1175 ... safe update mode`** 가 나오면 위 `SET` 두 줄을 함께 실행한다.
> 키 컬럼 조건을 붙여도 된다: `DELETE FROM game.save_slots WHERE id > 0;`
>
> 이것은 **MySQL 서버 규칙이 아니라 Workbench 가 접속 시 거는 클라이언트 설정**이다.
> 그래서 앱의 `DbCleaner` 는 같은 `DELETE` 를 아무 문제 없이 실행한다 — 앱 커넥션에는 그 설정이 없다.
> 같은 SQL 이 창에 따라 되고 안 되는 이유이고, "어느 층이 막았는가" 의 또 다른 사례다.

---

## 6. 결과 기록

**전부 통과 — 2026-08-29.**

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §0 선행 조건 | 테이블 9/9, qwer 버전 ≥1, 사용자 존재 | ✅ | Workbench safe update mode(1175) 로 정리가 한 번 막혔다 — `SET SQL_SAFE_UPDATES=0` 으로 해소 |
| §1 자동 테스트 | 전체 **48건** | ✅ | **첫 시도는 `up-to-date` 로 0건 실행되고도 BUILD SUCCESSFUL 이었다.** `cleanTest` 필요 |
| §2 bootRun | 기동 성공 | ✅ | |
| §3.1 회차 생성 | 201 / 없는 사용자 404 | ✅ | `{"code":"NOT_FOUND","message":"사용자가 없습니다: id=999999"}` |
| §3.2 revision | 1 → 2, 슬롯 1행 | ✅ | |
| §3.3 없는 버전 | **404** | ✅ | `"콘텐츠 버전이 없습니다: qwer v99"` — FK 위반 400 이 아니다 |
| §3.4 스냅샷 | 객체로 왕복, 목록엔 없음 | ✅ | `@JsonRawValue` 가 record 컴포넌트에서 동작 |
| §3.5 기기 | 2행, deviceKey 없으면 목록에 남음 | ✅ | `devices` id 가 1, 3 (§아래) |
| §3.6 슬롯 번호 | 0·128 → 400 / 5·42·127 → 200 | ✅ | D-008. 목록에 슬롯 6개 |
| §3.7 종료 멱등 | 두 응답 동일 | ✅ | |
| §4 Workbench | 스냅샷이 JSON 으로 저장됨 | ✅ | 6행 전부 `node_name = qwer_EP02_01`, 163바이트 |

부수적으로 확인된 것:

- **MySQL 은 JSON 객체의 키를 길이 순으로 정렬한다.** 보낸 순서 `nodeName, lineId, …` 가 받은 순서 `lineId(6), nodeName(8), variables(9), StageState(10), ProgressionState(16)` 로 바뀌었다. 구분자도 `": "` 로 공백이 붙는다 — M1 에서 5,686→3,581 바이트가 나온 것과 같은 이유이며, D-006 이 감수하기로 한 대가가 응답에 그대로 보인다.
- **`ON DUPLICATE KEY UPDATE` 는 AUTO_INCREMENT 를 소비한다.** `devices` id 가 1, 3 이고 2 가 없다. 중복 확인 **전에** 값을 할당하고, 갱신 경로로 가면 버리기 때문이다. 해롭지 않지만 **id 는 식별자이지 카운터가 아니라는** 또 하나의 증거다.
- `last_seen_at` 이 device-A 만 갱신됐다(20:48:19) — upsert 의 UPDATE 절이 실제로 일한다.
- **`{"device":null}` 이지 키가 빠지는 것이 아니다.** `@JsonInclude(NON_NULL)` 은 `ErrorResponse` 에만 붙어 있다. 테스트의 `doesNotExist()` 는 null 도 통과하므로 단언은 맞다.
- **깨진 JSON 을 보내면 우리 형식이 아닌 Spring 기본 형식이 나간다** (`{timestamp,status,error,path}`). `HttpMessageNotReadableException` 이 `GlobalExceptionHandler` 를 거치지 않는다 → M6-8 첫 항목.
