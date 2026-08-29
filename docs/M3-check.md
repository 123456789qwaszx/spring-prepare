# M3 검증 절차 — 선택 이력·이벤트 로그 (+ D-009 시간대 전환)

> PLAN §2.6. 결과는 §7 표에. 선행: M2 `검증됨`.
> **이번 M은 스키마 변경은 없지만 접속 설정과 기존 데이터가 바뀐다.** §0·§1이 그 작업이고, 건너뛰면 §4 시각 확인이 반드시 틀린다.

창은 셋이다. 어느 창에서 하는지 매 절 첫 줄에 적어 둔다.

| 이름 | 무엇 |
|---|---|
| **Workbench** | MySQL Workbench의 SQL 편집기 |
| **터미널 ①** | `gradlew` 를 돌리는 PowerShell (빌드·테스트·서버) |
| **터미널 ②** | API를 호출하는 PowerShell |

---

## 0. 접속 설정 바꾸기 (D-009) — 아미야만 할 수 있는 작업

두 파일은 `.gitignore` 되어 있어 이쪽에서 고칠 수 없다. **직접 연다.**

| 파일 | 바꿀 줄 |
|---|---|
| `src/main/resources/application-local.properties` | `spring.datasource.url=` |
| `src/test/resources/application-test.properties` | `spring.datasource.url=` |

바꾸기 **전** (M2까지):
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/game?serverTimezone=Asia/Seoul
```

바꾼 **후**:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/game?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
```

테스트 쪽은 DB 이름만 다르다 (`game_test`). **`game` 으로 바꿔 쓰지 않도록 주의** — 테스트가 개발 데이터를 지운다 (D-002).

> **왜 두 가지를 다 쓰는가**
> - `connectionTimeZone=UTC` — `DATETIME` 을 `OffsetDateTime` 으로 **읽을 때** UTC로 해석하라는 뜻.
> - `forceConnectionTimeZoneToSession=true` — 세션의 `time_zone` 도 UTC로 맞춘다. **기본값이 `false` 라서 안 쓰면 아무 일도 일어나지 않는다.**
>   이게 없으면 DB DEFAULT `CURRENT_TIMESTAMP` 가 채우는 `created_at` 은 MySQL 서버 시간대(KST)로, 앱이 넣는 `chosen_at` 은 UTC로 들어간다 — **한 테이블 안에서 시간대가 갈린다.**
> - `serverTimezone` 은 `connectionTimeZone` 의 옛 이름(별칭)이다. 지운 게 아니라 새 이름으로 쓴 것이다.

`.example` 파일도 함께 갱신되어 있다 (`src/test/resources/application-test.properties.example`). 다음에 환경을 새로 만들 때 그쪽을 복사하면 된다.

---

## 1. 기존 KST 데이터 폐기 — **Workbench**

D-009: *"기존 개발용 KST 데이터는 폐기하고 UTC 기준으로 재생성한다."*

섞어 두면 어느 행이 KST고 어느 행이 UTC인지 **행만 봐서는 알 수 없다.** 그게 폐기하는 이유다.

### 1.0 서버부터 끈다 — **터미널 ①**

M2 검증 때 띄운 `bootRun` 이 살아 있으면 안 된다. 이유가 둘이다.

- 그 프로세스는 **옛 URL(KST)로 커넥션 풀을 이미 잡았다.** 파일만 고쳐 봐야 재시작 전에는 안 읽힌다.
- 지우는 도중 요청이 들어오면 새 행이 생긴다.

```powershell
# 터미널 ① 에서 Ctrl+C  → Terminate batch job (Y/N)? → Y
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "아직 살아 있음" } else { "종료됨" }
```
"아직 살아 있음" 이면:
```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### 1.1 지금 무엇이 들어 있는지 먼저 본다

```sql
SELECT 'users' t, COUNT(*) n FROM game.users
UNION ALL SELECT 'chapter_contents',  COUNT(*) FROM game.chapter_contents
UNION ALL SELECT 'chapter_episodes',  COUNT(*) FROM game.chapter_episodes
UNION ALL SELECT 'game_definitions',  COUNT(*) FROM game.game_definitions
UNION ALL SELECT 'playthroughs',      COUNT(*) FROM game.playthroughs
UNION ALL SELECT 'save_slots',        COUNT(*) FROM game.save_slots
UNION ALL SELECT 'devices',           COUNT(*) FROM game.devices
UNION ALL SELECT 'choice_history',    COUNT(*) FROM game.choice_history
UNION ALL SELECT 'event_log',         COUNT(*) FROM game.event_log;
```

M2까지 만들어 둔 것이 보인다. `choice_history` 와 `event_log` 는 **0** 이어야 한다 (아직 만든 적 없다).

### 1.2 전부 지운다 — 자식 → 부모 순서

```sql
SET SQL_SAFE_UPDATES = 0;

DELETE FROM game.choice_history;
DELETE FROM game.event_log;
DELETE FROM game.save_slots;
DELETE FROM game.playthroughs;
DELETE FROM game.chapter_episodes;
DELETE FROM game.chapter_contents;
DELETE FROM game.game_definitions;
DELETE FROM game.devices;
DELETE FROM game.users;

SET SQL_SAFE_UPDATES = 1;
```

`game_test` 도 같은 방식으로 비운다 (테이블 이름 앞을 `game_test.` 로).
테스트는 매번 `DbCleaner` 로 지우지만, **지금 남아 있는 KST 행은 첫 테스트가 돌기 전까지 살아 있다** — 손으로 한 번 비워 두는 편이 헷갈리지 않는다.

> **순서가 M2-check와 다르다.** `DbCleaner` 와 같은 순서(자식 → 부모)이고, M3에서 `choice_history` · `event_log` 가 맨 앞에 추가됐다.
> `choice_history` 는 `save_slots` 를, `event_log` 는 `playthroughs` 를 가리키므로 둘 다 슬롯·회차보다 **먼저** 지운다.
>
> `Error Code: 1175 safe update mode` 는 Workbench의 클라이언트 설정이다 (M2 F22). 위 `SET` 두 줄이 그것을 끈다.

### 1.3 지워졌는지 확인

§1.1의 쿼리를 다시 돌린다. **`game` · `game_test` 양쪽 다 전부 0.**

### 1.4 `DELETE` 는 AUTO_INCREMENT 를 되돌리지 않는다

지운 뒤 사용자를 새로 만들면 **id 가 1이 아니다.** M0에서 alice·bob 을 만들었다면 다음은 3이다.

버그가 아니라 정의다. `AUTO_INCREMENT` 는 "몇 개 있나"가 아니라 "다음 번호는 무엇인가"를 들고 있고, `DELETE` 는 행만 지운다.
M2에서 본 것과 같은 이야기다 — `devices` id 가 1, 3 이었던 것(F18). **id 는 식별자이지 카운터가 아니다.**

그래서 §4의 `$USER_ID` · `$PT` 는 **문서의 숫자가 아니라 응답이 준 값**을 넣는다. 이게 M2에서 전 요청이 404 났던 바로 그 자리다.

되돌리고 싶으면(선택):
```sql
ALTER TABLE game.users            AUTO_INCREMENT = 1;
ALTER TABLE game.playthroughs     AUTO_INCREMENT = 1;
ALTER TABLE game.save_slots       AUTO_INCREMENT = 1;
ALTER TABLE game.devices          AUTO_INCREMENT = 1;
ALTER TABLE game.chapter_contents AUTO_INCREMENT = 1;
ALTER TABLE game.game_definitions AUTO_INCREMENT = 1;
ALTER TABLE game.choice_history   AUTO_INCREMENT = 1;
ALTER TABLE game.event_log        AUTO_INCREMENT = 1;
```
**테이블이 빈 상태에서만 한다.** 행이 남아 있는데 낮추면 다음 INSERT 가 기존 id 와 충돌한다.
권하지는 않는다 — 응답이 준 id 를 읽는 습관 쪽이 실전에 맞다.

---

## 2. 컴파일과 자동 테스트 — **터미널 ①**

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare
.\gradlew.bat compileTestJava
.\gradlew.bat cleanTest test
```

기대: **61건** (M0 6 + M1 23 + M2 18 + **M3 13** + `contextLoads` 1).

> **`cleanTest` 를 반드시 붙인다** (README 규칙 8, M2 F21). `gradlew test` 만 쓰면
> `5 actionable tasks: 5 up-to-date` 로 **한 건도 안 돌리고** `BUILD SUCCESSFUL` 을 낸다.
> 초록색이 아니라 **테스트 이름이 찍히는지**를 본다.

새로 도는 것은 `SaveHistoryApiTest` 13건이다.

| 메시지 | 원인 | 대응 |
|---|---|---|
| `오프셋이_붙어_와도_UTC로…` 실패, 기대 `11:40:19` / 실제 `20:40:19` | 쓰기 정규화가 빠졌다 | `UtcTime.toDbValue` 를 거치지 않는 바인딩이 있는지 확인 |
| 같은 테스트 실패, 실제 `02:40:19` | 세션 `time_zone` 이 UTC인데 값이 또 변환됐다 | URL에 `connectionTimeZone` 이 두 번 들어갔거나 `serverTimezone` 이 함께 남아 있는지 |
| `…T11:30Z` 로 나와 문자열 단언 실패 | Jackson은 **초가 0이면 초를 생략**한다 (F26) | 테스트 시각의 초를 0이 아닌 값으로 |
| `Cannot add or update a child row … fk_choice_episode` | 사전 검증이 안 걸리고 FK가 잡았다 | `resolveEpisodes` 가 호출되는지. 400이 아니라 이 오류가 나면 **롤백이 정상 경로가 된 것** |
| `Unknown column 'id' in 'field list'` | `findState` 의 SELECT 를 안 바꿨다 | `SELECT id, revision, updated_at` |
| `Table 'game_test.choice_history' doesn't exist` | 스키마 드리프트 (R4, M1에서 한 번 겪음) | `docs/schema.sql` 을 `game_test` 에 적용 |

---

## 3. 서버 실행 — **터미널 ①**

```powershell
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "사용 중" } else { "비어 있음" }
.\gradlew.bat bootRun
```

**"사용 중" 이면 반드시 죽인다.** M2 때 띄운 서버가 남아 있으면 그건 **옛 코드**라 `/saves/{n}/choices` 가 404이고, 무엇보다 **옛 접속 URL(KST)** 을 쓰고 있다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

> `-ErrorAction SilentlyContinue` 를 붙이는 이유: 일치하는 것이 없으면 빈 결과가 아니라 **오류**가 난다.
> "개체를 찾지 못했습니다" 는 실패가 아니라 **포트가 비어 있다는 뜻**이다.

---

## 4. API 시나리오 — **터미널 ②**

§1에서 데이터를 전부 지웠으므로 **사용자부터 다시 만든다.** 아래 블록을 통째로 붙여넣는다.

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare

function Call-Api {
    param($Method, $Uri, $Body)
    try {
        $r = if ($Body) { Invoke-WebRequest -Method $Method -Uri $Uri -ContentType 'application/json' -Body $Body -UseBasicParsing }
             else       { Invoke-WebRequest -Method $Method -Uri $Uri -UseBasicParsing }
        # $r.Content 를 쓰지 않는다 — 응답 헤더에 charset 이 없으면 PS 5.1 은 ISO-8859-1 로 디코딩해
        # 한글이 깨진다 (§7 부수 관찰). 바이트를 직접 UTF-8 로 읽는다.
        "{0}`n{1}" -f [int]$r.StatusCode, [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd(); $sr.Close()
        "{0}`n{1}" -f [int]$resp.StatusCode, $text
    }
}

$SNAP = '{"nodeName":"qwer_EP04_01","variables":{"$int":5},"ProgressionState":{"CurrentEpisodeId":"EP04_01"}}'

# 사용자와 회차를 만들고 **응답에서 id 를 꺼내 변수에 넣는다.**
# 손으로 넣지 않는 이유: DELETE 는 AUTO_INCREMENT 를 되돌리지 않아 id 가 1이 아니다 (§1.4).
# M2 에서 $PT 를 손으로 넣다가 이후 요청이 전부 404 났던 자리다.
$u = Invoke-WebRequest -Method POST -Uri "http://localhost:8080/users" -ContentType 'application/json' -Body '{"username":"amiya","password":"pw"}' -UseBasicParsing
$USER_ID = ($u.Content | ConvertFrom-Json).id
$p = Invoke-WebRequest -Method POST -Uri "http://localhost:8080/users/$USER_ID/playthroughs" -ContentType 'application/json' -UseBasicParsing
$PT = ($p.Content | ConvertFrom-Json).playthroughId

"USER_ID=$USER_ID  PT=$PT"
"확인: http://localhost:8080/playthroughs/$PT/saves/1"
```

마지막 두 줄이 **검증**이다. `USER_ID=3  PT=3` 처럼 숫자가 둘 다 보여야 한다.
`playthroughs//saves/1` 처럼 슬래시가 붙으면 변수가 비어 있는 것이니 거기서 멈춘다.

> 사용자 생성이 `409` 로 죽으면 `amiya` 가 이미 있는 것이다. §1 을 건너뛰었거나 이 블록을 두 번 돌린 경우다 —
> `SELECT id FROM game.users WHERE username='amiya';` 로 id 를 찾아 `$USER_ID` 에 직접 넣는다.

> **변수는 창과 함께 죽는다.** 창을 새로 열면 이 블록을 다시 붙여넣는다.
> `$SNAP` 이 비면 `"snapshot":,` 라는 깨진 JSON이 만들어지고, 응답이 `{"timestamp","status","error","path"}` 형식으로 온다 —
> 그 형식이 보이면 **서버 코드가 아니라 보낸 요청**을 먼저 의심한다 (M2 F20).

> `$PID` 를 쓰지 않는 이유: PowerShell의 자동 변수(현재 프로세스 ID)라 읽기 전용이다.

### 4.2 EventKey가 든 콘텐츠 수입

`qwer.progression.json` 은 EventKey가 전부 비어 있어 이벤트를 걸 수 없다. M3용 fixture를 올린다.

```powershell
$fx = [IO.File]::ReadAllBytes("src\test\resources\content\qwer-events.progression.json")
Invoke-WebRequest -Method POST -Uri "http://localhost:8080/content/chapters" `
    -ContentType 'application/json' -Body $fx -UseBasicParsing | ForEach-Object { $_.StatusCode; $_.Content }
```
기대: `201` / `{"chapterId":"qwer-events","version":1,"episodeCount":8,...}`

> `Call-Api` 를 안 쓰고 직접 부르는 이유: 본문을 **문자열이 아니라 바이트로** 보내야 한다.
> checksum은 바이트의 함수이고(M1), PowerShell이 인코딩을 건드리면 매번 다른 버전이 생긴다.

색인에 EventKey가 들어갔는지 확인 — **Workbench**:
```sql
SELECT episode_id, title, event_key, option_count
FROM game.chapter_episodes
WHERE chapter_content_id = (SELECT id FROM game.chapter_contents WHERE chapter_id='qwer-events' AND version=1)
ORDER BY episode_id;
```
기대: 8행. `EP03_01`=`MILESTONE_MIDPOINT`, `EP04_01`=`ENDING_A`, `EP04_02`=`ENDING_B`, 나머지 5개는 빈 문자열.

### 4.3 선택 3 + 이벤트 1 — 한 요청, 세 테이블

**여기서 시각을 일부러 `+09:00` 으로 보낸다.** 규약(D-009)은 `Z` 지만, 어긴 요청이 와도 서버가 UTC로 정규화하는지 보려면 어긴 쪽을 넣어야 한다.

```powershell
$b1 = @"
{"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP04_01",
 "snapshot":$SNAP,"playSeconds":600,"deviceKey":"device-A",
 "choices":[
   {"seq":1,"episodeId":"EP01","optionIndex":0,"chosenAt":"2026-08-29T20:40:19+09:00"},
   {"seq":2,"episodeId":"EP02_01","optionIndex":0,"chosenAt":"2026-08-29T11:41:23Z"},
   {"seq":3,"episodeId":"EP03_01","optionIndex":1,"chosenAt":"2026-08-29T11:42:31Z"}
 ],
 "events":[{"episodeId":"EP04_01","occurredAt":"2026-08-29T11:43:47Z"}]}
"@
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/1" $b1
```
기대: `200` / `{"revision":1,"updatedAt":"...Z","acceptedChoices":3,"acceptedEvents":1}`

**`updatedAt` 끝에 `Z` 가 붙어 있는지 본다.** 없으면 D-009가 안 걸린 것이다.

### 4.4 없는 에피소드 → 400, 그리고 **아무것도 남지 않는다**

M3의 핵심이다. seq 4는 멀쩡하고 seq 5만 틀렸다.

```powershell
$bad = @"
{"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP04_01",
 "snapshot":$SNAP,"playSeconds":700,"deviceKey":"device-A",
 "choices":[
   {"seq":4,"episodeId":"EP03_02","optionIndex":0,"chosenAt":"2026-08-29T20:50:11Z"},
   {"seq":5,"episodeId":"EP99_NOPE","optionIndex":0,"chosenAt":"2026-08-29T20:51:13Z"}
 ],
 "events":[{"episodeId":"EP04_02","occurredAt":"2026-08-29T20:52:17Z"}]}
"@
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/1" $bad
```
기대: `400` / `{"code":"BAD_REQUEST","message":"이 콘텐츠 버전에 없는 에피소드입니다: EP99_NOPE"}`

**`{"code":"CONSTRAINT_VIOLATION"}` 이 나오면 사전 검증이 안 걸리고 FK가 잡은 것이다** — 결과는 같아 보여도 다른 일이다 (M3.md §3-2).

바로 이어서 슬롯 상태를 본다:
```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves/1"
```
기대: **`revision` 이 여전히 `1`**, `playSeconds` 가 여전히 `600`.
멀쩡했던 seq 4도, 이벤트 `ENDING_B` 도 들어가지 않았다. 확인은 §5에서 SQL로.

### 4.5 EventKey 없는 에피소드에 이벤트 → 400

```powershell
$noKey = @"
{"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP01",
 "snapshot":$SNAP,"events":[{"episodeId":"EP02_01","occurredAt":"2026-08-29T20:55:19Z"}]}
"@
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/2" $noKey
```
기대: `400` / `"EventKey 가 없는 에피소드에는 이벤트를 기록할 수 없습니다: EP02_01"`

`event_key` 컬럼은 `NOT NULL` 이지만 **빈 문자열은 `NOT NULL` 을 통과한다.** DB가 못 막는 것을 서비스가 막는 자리다.

### 4.6 같은 seq 재전송 → 409

```powershell
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/1" $b1
```
기대: `409` / `{"code":"DUPLICATE","message":"이미 존재하는 값입니다."}`

**M4에서 이것이 `200 replayed` 로 바뀐다.** 지금 409인 것을 확인해 두는 것이 M4의 출발점이다.
그리고 `GET .../saves/1` 로 `revision` 이 **아직 1** 인지 본다 — 실패한 요청은 흔적을 남기지 않는다.

### 4.7 조회 — JOIN과 증분

```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PT/events"
```
기대: `200` / 1건. `eventKey=ENDING_A`, `chapterId=qwer-events`, `chapterVersion=1`,
**`chapterDisplayName=이벤트 테스트 챕터`**, `episodeId=EP04_01`, `occurredAt` 끝에 `Z`.

`eventKey` 는 **클라가 보낸 적이 없는 값**이다. 서버가 `chapter_episodes` 에서 찾아 넣었다.

```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves/1/choices"
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves/1/choices?afterSeq=2"
```
기대: 3건 → 1건 (seq 3만).

```powershell
Call-Api GET "http://localhost:8080/playthroughs/999999/events"
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves/9/choices"
```
기대: 둘 다 `404`. **"회차가 없다"(404)와 "이벤트가 0개"(빈 배열)는 다른 사실이다.**

### 4.8 이력 없이 세이브만 — M2 요청이 그대로 통한다

```powershell
$m2only = @"
{"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP01",
 "snapshot":$SNAP,"playSeconds":30,"deviceKey":"device-B"}
"@
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/3" $m2only
```
기대: `200` / `acceptedChoices=0, acceptedEvents=0`.
`choices`·`events` 는 없어도 되는 필드다 — 세이브만 올리는 것이 정상 경로이기 때문이다.

---

## 5. Workbench로 직접 보기 — **시간대가 진짜 맞았는지**

### 5.1 저장된 벽시계를 문자열로 본다

```sql
SELECT seq, episode_id, option_index,
       DATE_FORMAT(chosen_at,   '%Y-%m-%d %T') AS chosen_at_raw,
       DATE_FORMAT(received_at, '%Y-%m-%d %T') AS received_at_raw
FROM game.choice_history ORDER BY seq;
```

기대:

| seq | 보낸 값 | chosen_at_raw | 왜 |
|---|---|---|---|
| 1 | `20:40:19+09:00` | `2026-08-29 11:40:19` | **9시간 빠진 UTC.** 서버가 정규화했다 |
| 2 | `11:41:23Z` | `2026-08-29 11:41:23` | 이미 UTC → 그대로 |
| 3 | `11:42:31Z` | `2026-08-29 11:42:31` | 그대로 |

**seq 1이 `20:40:19` 로 보이면 쓰기 정규화가 빠진 것이다.** 이게 D-009의 전부다.

> 기대값은 **"보낸 값 − 오프셋"** 이지 무조건 9시간을 빼는 게 아니다.
> `Z` 로 온 값은 이미 UTC라 바뀌지 않는다 — 바뀌면 그게 이중 변환 버그다.
> (초안에서 이 표가 `Z` 로 보낸 값에도 9시간을 뺀 값을 적어 두었었다. 실행 검증에서 잡혔다.)

`received_at_raw` 는 DB DEFAULT가 채운 **서버가 받은 시각**이고 UTC여야 한다 —
지금 시각을 KST로 알고 있다면 거기서 9시간 뺀 값 근처다. `chosen_at` 과 `received_at` 이 두 컬럼으로 나뉜 이유가 여기 보인다.

### 5.2 DB DEFAULT와 앱이 넣은 값이 같은 시간대인가

`forceConnectionTimeZoneToSession` 을 안 켜면 여기서 9시간이 벌어진다.

```sql
SELECT @@session.time_zone AS session_tz, @@global.time_zone AS global_tz, NOW() AS db_now;
```
> Workbench는 **자기 커넥션**을 쓰므로 여기 나오는 `session_tz` 는 앱의 것이 아니다. 참고값이다.

앱 커넥션의 결과를 보는 방법은 **앱이 넣은 행끼리 비교**하는 것이다:
```sql
SELECT DATE_FORMAT(created_at, '%Y-%m-%d %T') AS user_created_at FROM game.users;          -- DB DEFAULT
SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %T') AS slot_updated_at FROM game.save_slots;     -- DB ON UPDATE
SELECT DATE_FORMAT(imported_at,'%Y-%m-%d %T') AS content_imported FROM game.chapter_contents;
```
**셋 다 §5.1의 `received_at` 과 같은 시간대(= UTC)여야 한다.** 하나만 9시간 다르면 그건 세션 `time_zone` 이 안 바뀐 것이다.

### 5.3 §4.4의 실패가 아무것도 남기지 않았는지

```sql
SELECT COUNT(*) AS choices FROM game.choice_history;   -- 3  (seq 4가 들어갔다면 4)
SELECT COUNT(*) AS events  FROM game.event_log;        -- 1  (ENDING_B가 들어갔다면 2)
SELECT slot_no, revision, play_seconds FROM game.save_slots ORDER BY slot_no;
```
기대: 선택 **3**, 이벤트 **1**, 슬롯 1의 `revision=1` · `play_seconds=600`.
`play_seconds` 가 700이면 슬롯 upsert만 커밋되고 배치가 롤백된 것 — 트랜잭션 경계가 깨졌다는 뜻이다.

### 5.4 복합 FK와 JOIN을 눈으로

```sql
SELECT e.event_key, c.chapter_id, c.version, c.display_name, ep.title,
       DATE_FORMAT(e.occurred_at, '%Y-%m-%d %T') AS occurred_at_raw
FROM game.event_log e
JOIN game.chapter_contents c  ON c.id = e.chapter_content_id
JOIN game.chapter_episodes ep ON ep.chapter_content_id = e.chapter_content_id
                             AND ep.episode_id         = e.episode_id;
```
`ON` 이 **두 컬럼**인 것이 복합 FK다. "어느 버전의 어느 에피소드"는 한 쌍이라야 뜻이 있다.
기대 1행: `ENDING_A / qwer-events / 1 / 이벤트 테스트 챕터 / 엔딩 A`.

---

## 6. 정리

**터미널 ①** 에서 `Ctrl+C` → `Terminate batch job (Y/N)?` → `Y`

```powershell
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "아직 살아 있음" } else { "종료됨" }
```

수동 확인 데이터는 **지우지 않고 남겨 둔다.** M4가 이 회차와 슬롯 위에서 `baseRevision` 을 시험한다.
지우고 싶으면 §1.2의 블록을 그대로 쓴다.

---

## 7. 결과 기록

**전부 통과 — 2026-08-29.**

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §0 URL 교체 | 두 파일 모두 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | ✅ | |
| §1 데이터 폐기 | `game`·`game_test` 9개 테이블 전부 0 | ✅ | |
| §2 자동 테스트 | **61건** | ✅ | `3 executed`. **첫 컴파일에 오류 0** — 새 파일 13 + 수정 14를 무컴파일로 작성 |
| §3 bootRun | 기동 성공 | ✅ | |
| §4.2 fixture 수입 | 201, EventKey 3개 | ✅ | `episodeCount: 8`, 색인에 `MILESTONE_MIDPOINT`/`ENDING_A`/`ENDING_B` |
| §4.3 선택 3 + 이벤트 1 | 200, accepted 3/1, `updatedAt` 에 `Z` | ✅ | `"updatedAt":"2026-08-29T12:53:25Z"` |
| §4.4 없는 에피소드 | **400 BAD_REQUEST**, revision 불변 | ✅ | `"이 콘텐츠 버전에 없는 에피소드입니다: EP99_NOPE"` — `CONSTRAINT_VIOLATION` 이 아니다 |
| §4.5 EventKey 없음 | 400 | ✅ | 슬롯 2가 아예 안 생겼다 |
| §4.6 seq 재전송 | **409 DUPLICATE**, revision 불변 | ✅ | M4에서 200 replayed로 바뀔 자리 |
| §4.7 조회 | events 1건 + 표시명, choices 3 → 1 | ✅ | `afterSeq=2` → seq 3 하나 |
| §4.7 404 둘 | 회차 404, 슬롯 404 | ✅ | `"회차가 없습니다: id=999999"` / `"슬롯이 없습니다: 회차 3 슬롯 9"` |
| §4.8 이력 없는 PUT | 200, accepted 0/0 | ✅ | |
| §5.1 시각 | seq 1이 `11:40:19` | ✅ | `Z` 로 보낸 seq 2·3은 그대로 — 이중 변환 없음 |
| §5.2 시간대 일치 | DEFAULT와 앱 값이 같은 시간대 | ✅ | `users.created_at` 12:52:18, `save_slots.updated_at` 12:53·12:56 — 전부 UTC |
| §5.3 원자성 | 선택 3, 이벤트 1, 슬롯 1·3만 | ✅ | 슬롯 1 `rev 1 / 600초`, 슬롯 3 `rev 2 / 30초`. **슬롯 2 없음** |
| §5.4 복합 FK JOIN | 1행, 표시명·제목 나옴 | ✅ | `ENDING_A / qwer-events / 1 / 이벤트 테스트 챕터 / 엔딩 A` |

부수적으로 확인된 것:

- **`updated_at` 이 UTC로 찍혔다** (`12:53:25Z`, 당시 한국 시각 21:53). 이 컬럼은 앱이 아니라 **DB의 `DEFAULT CURRENT_TIMESTAMP` 가 채운다** — 즉 `forceConnectionTimeZoneToSession=true` 가 세션 `time_zone` 을 실제로 바꿨다는 증거다. 이게 없었다면 `chosen_at` 만 UTC이고 `created_at`·`updated_at`·`received_at` 은 KST라, 한 테이블 안에서 시간대가 갈렸을 것이다. **D-009에서 이 플래그가 절반을 담당한다.**
- **PowerShell 5.1이 응답 한글을 깨뜨렸다 — 서버 문제가 아니다.** `$r.Content` 는 응답 헤더에 charset 이 없으면 **ISO-8859-1** 로 디코딩한다. `이`(EC 9D B4) 가 `ì..` 가 되고 제어문자가 섞여 콘솔 출력이 끊기기까지 했다.
  - 같은 창의 **400 응답은 한글이 멀쩡했다.** `Call-Api` 의 `catch` 절은 `StreamReader`(기본 UTF-8)로 읽기 때문이다. 성공 경로와 실패 경로가 다른 방식으로 디코딩되고 있었다.
  - **왜 charset 이 없나**: 컨버터가 다르다. M1의 `GET /content/chapters/{id}/{v}` 는 `ResponseEntity<String>` 이라 `StringHttpMessageConverter` 가 처리하고 `charset=UTF-8` 을 붙인다(M1에서 확인한 예외 규칙). M3의 `/events` 는 객체를 돌려줘 Jackson 컨버터가 처리하는데, 얘는 안 붙인다 — JSON은 규격상 UTF-8이라 안 붙이는 쪽이 정석이다.
  - 대응: `Call-Api` 가 `$r.RawContentStream` 을 UTF-8로 직접 읽도록 고쳤다(§4). **서버는 고치지 않았다** — 바이트는 처음부터 옳았다.
  - 남는 사실 하나: **응답 `Content-Type` 이 엔드포인트마다 다르다.** 무해하지만 M6(형식 통일)에서 볼 항목으로 올려 뒀다.
- **`DELETE` 후 첫 사용자 id 가 3, 회차 id 도 3이었다.** AUTO_INCREMENT 는 행 수가 아니라 다음 번호를 들고 있다(M2 F18과 같은 이야기). 그래서 §4 설정 블록이 id를 **응답에서 꺼내 대입**하도록 바뀌었다 — M2에서 손으로 넣다가 전 요청이 404 났던 자리를 절차로 막았다.
- **초안의 §5.1 기대표가 틀렸다.** `Z` 로 보낸 값에도 9시간을 뺀 값을 적어 뒀었다. 실제 결과(그대로 저장)가 옳다. 기대값은 "보낸 값 − 오프셋"이지 무조건 −9시간이 아니다. 실행 검증이 **문서의 오류**를 잡은 사례다.
