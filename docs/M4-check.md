# M4 검증 절차 — 멱등성과 충돌

> PLAN §2.6. 결과는 §6 표에. 선행: M3 `검증됨`, 결정 **D-010** 반영됨.
> **스키마 변경 없음**, 접속 설정 변경 없음. M3 처럼 사전 준비가 필요한 M이 아니다.

창은 둘이다. **Workbench** 와 **터미널 ①**(gradle·서버) / **터미널 ②**(API).

> **왜 "두 기기"를 두 터미널로 나누지 않았나**
> M4 계획서는 두 터미널로 A·B 를 재현하자고 했지만, 이 시나리오는 **순차**다 — A 가 저장한 *뒤에* B 가 낡은 base 로 보낸다.
> 진짜 동시성은 `SaveSlotConcurrencyTest` 가 스레드 둘로 이미 증명한다. 손으로 하는 쪽은 **응답 본문을 읽는 것**이 목적이라
> 창을 나누면 변수만 두 번 세팅하게 되고, 그게 M2 에서 사람이 단계를 건너뛴 원인이었다(3차 점검).
> 그래서 한 창에서 `$A_*` / `$B_*` 로 나눈다.

---

## 0. 선행 조건 확인 — **Workbench**

M3-check §6 에서 데이터를 지우지 않고 남겨 두었다. 콘텐츠만 있으면 된다.

```sql
SELECT id, chapter_id, version, display_name FROM game.chapter_contents WHERE chapter_id = 'qwer-events';
SELECT COUNT(*) AS episodes FROM game.chapter_episodes
WHERE chapter_content_id = (SELECT id FROM game.chapter_contents WHERE chapter_id='qwer-events' AND version=1);
SELECT id, username FROM game.users;
```

기대: `qwer-events` v1 이 있고, 에피소드 **8**, 사용자 최소 하나.
없으면 M3-check §4.2 로 fixture 를 다시 올린다.

> 회차·슬롯은 **새로 만든다.** M3 가 남긴 슬롯의 revision 이 지금 몇인지에 의존하면 절차가 깨지기 쉽다.
> M4 는 revision 을 다루는 M 이므로, 출발점을 아는 상태에서 시작하는 편이 낫다.

---

## 1. 컴파일과 자동 테스트 — **터미널 ①**

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare
.\gradlew.bat compileTestJava
.\gradlew.bat cleanTest test
```

기대: **70건** (M0 6 + M1 23 + M2 18 + M3 13 + **M4 9** + `contextLoads` 1).
M4 는 `SaveSlotConflictTest` 8 + `SaveSlotConcurrencyTest` 1 이다.

| 메시지 | 원인 | 대응 |
|---|---|---|
| 동시성 테스트에서 `200 의 수 expected 1 but was 2` | 조건부 UPDATE 가 아니라 예전 upsert 를 타고 있다 | `updateIfRevision` 이 호출되는지 |
| 동시성 테스트에서 둘 다 409 | 첫 조회가 트랜잭션 밖이거나 base 를 잘못 줬다 | 서비스 공개 메서드 하나가 트랜잭션 전체인지 |
| `Deadlock found when trying to get lock` | 두 트랜잭션이 행을 다른 순서로 잠갔다 | 항상 **기기 → 슬롯 → 이력** 순서. 재현되면 `SHOW ENGINE INNODB STATUS` |
| 기존 M2·M3 테스트가 400 | `baseRevision` 을 안 보내는 요청이 남아 있다 | 의도된 파괴다. 그 테스트에 base 를 추가 |
| `replayed` 가 늘 false | 재전송 판정이 UPDATE 뒤에 있다 | 판정 → UPDATE 순서 (M4.md C4) |

---

## 2. 서버 실행 — **터미널 ①**

```powershell
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "사용 중" } else { "비어 있음" }
.\gradlew.bat bootRun
```

"사용 중" 이면 **M3 때 띄운 옛 코드**다. 반드시 죽인다:
```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

---

## 3. 준비 — **터미널 ②**

통째로 붙여넣는다. id 는 응답에서 꺼내 대입한다 (M3-check §1.4 의 이유 그대로).

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare

function Call-Api {
    param($Method, $Uri, $Body)
    try {
        $r = if ($Body) { Invoke-WebRequest -Method $Method -Uri $Uri -ContentType 'application/json' -Body $Body -UseBasicParsing }
             else       { Invoke-WebRequest -Method $Method -Uri $Uri -UseBasicParsing }
        "{0}`n{1}" -f [int]$r.StatusCode, [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd(); $sr.Close()
        "{0}`n{1}" -f [int]$resp.StatusCode, $text
    }
}

$SNAP = '{"nodeName":"qwer_EP01","variables":{"$int":1}}'

# 세이브 본문을 만드는 함수. base 와 기기, 그리고 choices 를 갈아 끼운다.
function Body {
    param([long]$Base, [int]$Sec, [string]$Dev, [string]$Choices = "")
    $c = if ($Choices) { ",""choices"":[$Choices]" } else { "" }
    "{""chapterId"":""qwer-events"",""chapterVersion"":1,""currentEpisodeId"":""EP02_01""," +
    """snapshot"":$SNAP,""playSeconds"":$Sec,""deviceKey"":""$Dev"",""baseRevision"":$Base$c}"
}
function Choice {
    param([int]$Seq, [string]$Ep)
    "{""seq"":$Seq,""episodeId"":""$Ep"",""optionIndex"":0,""chosenAt"":""2026-08-30T0${Seq}:11:07Z""}"
}

# 사용자와 회차를 새로 만든다
$u = Invoke-WebRequest -Method POST -Uri "http://localhost:8080/users" -ContentType 'application/json' -Body '{"username":"m4","password":"pw"}' -UseBasicParsing
$USER_ID = ($u.Content | ConvertFrom-Json).id
$p = Invoke-WebRequest -Method POST -Uri "http://localhost:8080/users/$USER_ID/playthroughs" -ContentType 'application/json' -UseBasicParsing
$PT = ($p.Content | ConvertFrom-Json).playthroughId
$URL = "http://localhost:8080/playthroughs/$PT/saves/1"

"USER_ID=$USER_ID  PT=$PT"
"확인: $URL"
```

`USER_ID` 와 `PT` 에 **숫자가 둘 다** 보여야 한다.
(사용자 생성이 409 면 `m4` 가 이미 있다 — 이름을 `m4b` 등으로 바꾼다.)

---

## 4. 다섯 갈래를 하나씩

### 4.1 신규 — base 0

```powershell
Call-Api PUT $URL (Body 0 100 "device-A" (Choice 1 "EP01"))
```
기대: `200` / `{"revision":1,"updatedAt":"…Z","acceptedChoices":1,"acceptedEvents":0,"replayed":false}`

### 4.2 재전송 — 같은 요청을 그대로 다시

응답을 못 받은 클라가 재전송하는 상황이다. **같은 명령을 한 번 더 실행한다.**

```powershell
Call-Api PUT $URL (Body 0 100 "device-A" (Choice 1 "EP01"))
```
기대: `200` / **`"replayed":true`**, `"revision":1` (**오르지 않았다**), `"acceptedChoices":0`

M3 에서는 여기가 `409 DUPLICATE` 였다. `(save_slot_id, seq)` UNIQUE 가 막았기 때문이다.
M4 는 그 전에 "내가 방금 보낸 그 요청"임을 알아본다 — `revision == base+1` 이고 seq 가 전부 이미 있다.

### 4.3 정상 — base 를 올려서

```powershell
Call-Api PUT $URL (Body 1 200 "device-A" (Choice 2 "EP02_01"))
```
기대: `200` / `"revision":2`, `"replayed":false`, `"acceptedChoices":1`

### 4.4 충돌 — B 가 낡은 base 로

B 는 revision 1 일 때의 상태를 들고 있다. 그 사이 A 가 한 번 더 썼다.

```powershell
Call-Api PUT $URL (Body 1 999 "device-B" (Choice 3 "EP03_01"))
```
기대: `409` /
```json
{"code":"CONFLICT","message":"다른 기기가 먼저 저장했습니다. baseRevision=1",
 "current":{"slotNo":1,"chapterId":"qwer-events","chapterVersion":1,
            "currentEpisodeId":"EP02_01","revision":2,"playSeconds":200,
            "updatedAt":"…Z","device":"device-A"}}
```

**`current` 가 있는 것이 요점이다.** 클라는 다시 GET 하지 않고도 "무엇과 부딪혔는지"를 안다 —
`device-A` 가 `revision 2` 로 `200초` 까지 저장해 뒀다는 것. 이 필드들이 M8 충돌 UI 가 보여줄 것이다.

바로 이어서 아무것도 안 들어갔는지 본다:
```powershell
Call-Api GET $URL
```
기대: `revision` 2, `playSeconds` 200, `device` `device-A`. B 의 999 초는 흔적도 없다.

### 4.5 force — 사용자가 "덮어쓰기"를 골랐다

B 는 **409 가 알려준 revision 2** 를 base 에 넣어 다시 보낸다.
seq 1·2 는 이미 있고 3 만 새것이다.

```powershell
$body = Body 2 500 "device-B" ((Choice 1 "EP01") + "," + (Choice 2 "EP02_01") + "," + (Choice 3 "EP03_01"))
Call-Api PUT "$URL`?force=true" $body
```
기대: `200` / `"revision":3`, **`"acceptedChoices":1`**

보낸 것은 3건인데 기록한 것은 1건이다. **여기서 `acceptedChoices` 가 처음으로 실제 정보를 담는다** —
M3 까지는 늘 보낸 수와 같았다(하나라도 틀리면 400 이고 전부 롤백됐으므로).

```powershell
Call-Api GET "http://localhost:8080/playthroughs/$PT/saves/1/choices"
```
기대: seq **1, 2, 3** 세 건. A 의 이력이 남은 채 B 의 새것이 더해졌다.

### 4.6 force 여도 낡은 base 면 409 (D-010)

```powershell
Call-Api PUT "$URL`?force=true" (Body 1 700 "device-B")
```
기대: `409 CONFLICT`, `current.revision` 3.

**force 는 revision 조건을 건너뛰지 않는다.** 건너뛰면 409 를 받은 뒤 force 를 보내기까지 사이에 끼어든
*세 번째* 기기를 놓친다. force 는 "무조건 덮어쓰기"가 아니라 **"내가 본 그 상태 위에 덮어쓰기"** 다.

### 4.7 choices 없는 요청은 판정하지 않는다 (D-010)

세이브만 올리는 요청 — playSeconds 자동 저장 같은 **정상 경로**다.

```powershell
Call-Api PUT $URL (Body 2 800 "device-A")     # 낡은 base
Call-Api PUT $URL (Body 3 800 "device-A")     # 맞는 base
```
기대: 첫 번째 `409 CONFLICT`, 두 번째 `200 revision 4`.

PLAN 원문은 첫 번째에 200 을 주라고 했지만 D-010 이 뒤집었다.
200 을 주면 **충돌을 재전송으로 오인**해, 다른 기기가 덮었는데도 클라는 "저장됐다"고 믿는다.

### 4.8 baseRevision 자체

```powershell
Call-Api PUT "http://localhost:8080/playthroughs/$PT/saves/7" (Body 1 10 "device-A")
Call-Api PUT $URL '{"chapterId":"qwer-events","chapterVersion":1,"currentEpisodeId":"EP01","snapshot":{"a":1}}'
```
기대: 첫 번째 `400` ("슬롯이 없는데 baseRevision 이 1"), 두 번째 `400` ("baseRevision 이 없습니다").

두 번째가 M2·M3 요청 형식과의 **의도된 단절**이다.

---

## 5. Workbench 로 확인

```sql
SELECT slot_no, revision, play_seconds, current_episode_id,
       (SELECT device_key FROM game.devices d WHERE d.id = s.device_id) AS device
FROM game.save_slots s
WHERE s.playthrough_id = <$PT>
ORDER BY slot_no;

SELECT seq, episode_id, DATE_FORMAT(chosen_at, '%Y-%m-%d %T') AS chosen_at_raw
FROM game.choice_history
WHERE save_slot_id = (SELECT id FROM game.save_slots WHERE playthrough_id = <$PT> AND slot_no = 1)
ORDER BY seq;
```

기대: 슬롯 1 하나, `revision 4`, `play_seconds 800`, `device-A`. 선택은 seq 1·2·3.
슬롯 7 은 **없다** (§4.8 이 400 으로 막았다).

> revision 이 4 인 이유를 세어 본다: 신규(1) → 재전송(안 오름) → 정상(2) → 충돌(안 오름) →
> force(3) → force 충돌(안 오름) → choices 없는 충돌(안 오름) → 정상(4).
> **실패한 요청은 revision 을 올리지 않는다** — 이 숫자가 그 증거다.

---

## 6. 정리

**터미널 ①** 에서 `Ctrl+C` → `Y`

```powershell
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { "아직 살아 있음" } else { "종료됨" }
```

데이터는 남겨 둔다 — M5 의 집계가 이력 위에서 돌아간다.

---

## 7. 결과 기록

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §1 자동 테스트 | **70건** | | 동시성 테스트가 이제 통과해야 한다 |
| §4.1 신규 | 200, revision 1 | | |
| §4.2 재전송 | 200 **replayed:true**, revision 그대로 | | M3 에서는 409 였다 |
| §4.3 정상 | 200, revision 2 | | |
| §4.4 충돌 | **409 CONFLICT + current** | | `current.device`=device-A, `revision`=2 |
| §4.5 force | 200, revision 3, **acceptedChoices 1** | | 이력 seq 1·2·3 |
| §4.6 force + 낡은 base | 409 | | force 는 조건을 건너뛰지 않는다 |
| §4.7 choices 없음 | 409 → (base 고치면) 200 | | D-010 |
| §4.8 base 검증 | 400 둘 | | |
| §5 최종 상태 | revision 4, 선택 3건, 슬롯 7 없음 | | |

부수적으로 확인된 것:

-
