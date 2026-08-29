# M2 검증 절차 — 회차·세이브 업로드/복구

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
.\gradlew.bat test --tests "com.sparta.springprepare.playthrough.*" --tests "com.sparta.springprepare.save.*"
```

기대: `PlaythroughApiTest` 6건 + `SaveSlotApiTest` 11건 = **17건**.

전체를 한 번 더 돌려 M0·M1이 깨지지 않았는지도 본다 (`DbCleaner`가 바뀌었다).

```powershell
.\gradlew.bat test
```
기대: M0 6 + M1 23 + M2 17 + `contextLoads` 1 = **47건**.

| 메시지 | 원인 |
|---|---|
| `Cannot delete or update a parent row` | `DbCleaner` 순서 문제. 자식이 부모보다 앞인지 확인 |
| `snapshot` 단언 실패, 값이 `"{\"...\"}"` | `@JsonRawValue` 가 안 먹었다 (M2.md C10) |
| `revision` 이 0 | INSERT 값에 1이 없다 (C11) |

---

## 2. 서버 실행

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen   # 아무것도 없어야 한다
.\gradlew.bat bootRun
```

M1 때 띄운 서버가 남아 있으면 **옛 코드**라 `/playthroughs/**` 가 404다. 반드시 죽이고 새로 띄운다.

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
        "{0}`n{1}" -f [int]$_.Exception.Response.StatusCode, $_.ErrorDetails.Message
    }
}

$USER_ID = 1     # §0 에서 확인한 값
$SNAP = '{"nodeName":"qwer_EP02_01","lineId":"line:0007","variables":{"$int":5},"StageState":{"slots":["c1"]},"ProgressionState":{"CurrentEpisodeId":"EP02_01"}}'
```

### 3.1 회차 만들기

```powershell
Call-Api POST "http://localhost:8080/users/$USER_ID/playthroughs"
```
기대: `201` / `{"playthroughId":1}` — 이 번호를 `$PID` 로 둔다.

```powershell
$PID = 1     # 위 응답 값
```

없는 사용자로도 해 본다.

```powershell
Call-Api POST 'http://localhost:8080/users/999999/playthroughs'
```
기대: `404 NOT_FOUND`. FK 위반(400)이 아니다 — 서비스가 먼저 조회하기 때문이다.

### 3.2 세이브 업로드 — revision 이 1, 2로 오른다

```powershell
$body1 = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":10,`"deviceKey`":`"device-A`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/1" $body1
```
기대: `200` / `{"revision":1,"updatedAt":"..."}`

같은 슬롯에 한 번 더:

```powershell
$body2 = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP02_01`",`"snapshot`":$SNAP,`"playSeconds`":25,`"deviceKey`":`"device-A`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/1" $body2
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
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/2" $bad
```
기대: `404 NOT_FOUND`.

서비스가 먼저 `chapter_contents` 를 조회하지 않았다면 FK 위반이 나서 `400 CONSTRAINT_VIOLATION` 이 됐을 것이다. **어느 쪽도 500은 아니지만, 클라에게 맞는 답은 "그 콘텐츠 버전이 서버에 없다"** 이므로 404다.

### 3.4 스냅샷 왕복 — 서버는 열지 않는다

```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PID/saves/1"
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
Call-Api GET "http://localhost:8080/playthroughs/$PID/saves"
```
기대: `snapshot` 키 자체가 없다. 목록 쿼리가 그 컬럼을 SELECT 하지 않기 때문이다.

### 3.5 기기 — 두 종류, 그리고 없는 경우

```powershell
$bodyB = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":5,`"deviceKey`":`"device-B`"}"
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/2" $bodyB

$noDev = "{`"chapterId`":`"qwer`",`"chapterVersion`":1,`"currentEpisodeId`":`"EP01`",`"snapshot`":$SNAP,`"playSeconds`":5}"
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/3" $noDev

Call-Api GET "http://localhost:8080/playthroughs/$PID/saves"
```

기대: 슬롯 셋. 1·2는 `device` 가 `device-A`/`device-B`, 3은 `device` 키가 없다(null).
**3번 슬롯이 목록에서 사라지면 안 된다** — `devices` 를 LEFT JOIN 한 이유다.

```sql
SELECT id, user_id, device_key, last_seen_at FROM game.devices;   -- 2행
```

### 3.6 슬롯 번호 — D-008

범위 밖은 400:

```powershell
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/0"   $body1
Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/128" $body1
```
기대: 둘 다 `400 BAD_REQUEST`. TINYINT 오버플로 500이 아니다.

**개수 상한은 없다:**

```powershell
foreach ($n in 5, 42, 127) {
    Call-Api PUT "http://localhost:8080/playthroughs/$PID/saves/$n" $body1
}
Call-Api GET "http://localhost:8080/playthroughs/$PID/saves"
```
기대: 전부 `200`, 목록에 슬롯 6개(1,2,3,5,42,127). 예전 계획의 "슬롯 4 → 400"을 대체하는 기준이다.

### 3.7 회차 목록과 종료

```powershell
Call-Api GET "http://localhost:8080/users/$USER_ID/playthroughs"
```
기대: `slotCount: 6`, `endedAt` 키 없음(진행 중).

```powershell
Call-Api POST "http://localhost:8080/playthroughs/$PID/end"
Call-Api POST "http://localhost:8080/playthroughs/$PID/end"
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
# 터미널 ① 에서 Ctrl+C
Get-NetTCPConnection -LocalPort 8080 -State Listen   # 비었는지 확인
```

```sql
-- 수동 확인 데이터 정리 (자식 → 부모). DbCleaner 와 같은 순서다.
DELETE FROM game.save_slots;
DELETE FROM game.playthroughs;
DELETE FROM game.devices;
```

---

## 6. 결과 기록

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §0 선행 조건 | 테이블 9/9, qwer 버전 ≥1, 사용자 존재 | | |
| §1 자동 테스트 | M2 17건, 전체 47건 | | |
| §2 bootRun | 기동 성공 | | |
| §3.1 회차 생성 | 201 / 없는 사용자 404 | | |
| §3.2 revision | 1 → 2, 슬롯 1행 | | |
| §3.3 없는 버전 | **404** | | |
| §3.4 스냅샷 | 객체로 왕복, 목록엔 없음 | | |
| §3.5 기기 | 2행, deviceKey 없으면 device null 이고 목록에 남음 | | |
| §3.6 슬롯 번호 | 0·128 → 400 / 5·42·127 → 200 | | D-008 |
| §3.7 종료 멱등 | 두 응답 동일 | | |
| §4 Workbench | 스냅샷이 JSON 으로 저장됨 | | |
