# M7 검증 절차 — Unity 저장 포트

> PLAN §2.6. 결과는 §6 표에. 선행: M6 `검증됨`, D-015·D-016·D-017, Unity 레포에 M7 코드 17파일 반영.
> **이번 M의 무대는 Unity 에디터다.** 서버 레포에서는 기동과 DB 확인만 한다.
> 완료 기준(계획서 §7): ① 비행기 모드 선택 5개 → 온라인 → `choice_history` 5행 seq 연속, ② 강제 종료 후 재실행 → 로컬에서 이어짐 + 큐 유지.

창은 **Unity 에디터**, **터미널 ①**(bootRun), **터미널 ②**(API·파일 확인), **Workbench** 넷이다.

---

## 0. 사전 준비

**(a) 서버 기동** (터미널 ①): M6-check §1 과 같다 — `.\gradlew.cmd bootRun`. Flyway `game` 확인.

**(b) 콘텐츠 수입 — 반드시 Unity 에셋 파일 그대로** (터미널 ②):

```powershell
$ADMIN = @{ 'X-Admin-Key' = '<application-local 의 admin-key>' }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/content/chapters `
  -ContentType 'application/json' -Headers $ADMIN `
  -InFile 'C:\Users\river\Documents\GitHub\ked-presentation-runtime\Assets\@Dialogue\ChapterProgression\qwer.progression.json'
```

- `-InFile` 이어야 한다 (M1-check 규칙, F45) — `Get-Content` 재인코딩은 **checksum 을 바꾼다**.
- D-015 가 여기 걸려 있다: 클라는 **이 파일의 바이트** SHA-256 으로 자기 버전을 찾는다.
  다른 사본을 올리면(줄바꿈 하나만 달라도) 클라가 버전을 못 찾고 동기화가 서는 것이 정상 동작이다.
- 이미 같은 checksum 이 수입돼 있으면 200(기존 버전 반환)도 정상. 응답의 `version` 을 적어 둔다.

**(c) Unity 컴파일**: `ked-presentation-runtime` 을 에디터로 연다. 콘솔에 컴파일 오류가 없어야 하고,
`Assets/Scripts/Save/`(12파일)와 `ProgressionReports.cs` 의 `.meta` 가 새로 생긴다 — 커밋에 포함한다.

**(d) 인스펙터**: VNAppBootstrap 의 **저장·동기화 (M7)** 헤더에 `Server Base Url` 이
`http://localhost:8080` 인지 확인 (씬에 직렬화된 옛 값이 있으면 갱신).

**(e) 저장 위치 파악** (터미널 ②) — 이후 모든 파일 확인이 여기서 일어난다:

```powershell
$SAVE = "$env:USERPROFILE\AppData\LocalLow\<Company>\<Product>"   # Unity Player Settings 의 값
dir $SAVE
```

이전 실행의 흔적이 있으면 **`saves\` 폴더와 `account.json` 을 지우고 시작한다** (깨끗한 첫 실행 — 계정까지 새로).

**키 둘** (VNAdvanceInputBindings 기본값): **4번** = 진행 시작 — 세이브가 있으면 이어하기, 없으면 새 게임. **5번** = **새 게임** — 못 보낸 큐를 밀어 보고(FlushAsync) → 큐 초기화 → 슬롯 삭제 → 시작. 5번은 서버에 **새 회차**를 만든다(이전 회차는 열린 채 남는다). 계정은 그대로라 §0(e) 의 `account.json` 삭제와는 다르다.

## 1. 온라인 첫 플레이 — 게스트 계정·회차·첫 동기화

플레이 모드 진입 → 타이틀에서 진행 시작 → **선택 2개** 커밋 후 정지.

| # | 확인 | 기대 |
|---|---|---|
| 1.1 | 콘솔 로그 | `[계정] 게스트 계정 생성 — guest-…` → `[동기화] 회차 생성 — playthroughId N` → `[동기화] 'qwer' = 서버 v<버전> (checksum 일치)` → `[동기화] 완료 — revision …` |
| 1.2 | `$SAVE\account.json` | `username: guest-<12hex>`, `userId`, `token`, `expiresAtUtc` 채워짐 |
| 1.3 | `$SAVE\saves\slot1.json` | `chapterId: qwer`, 마지막 선택의 도착 에피소드, `stats` |
| 1.4 | `$SAVE\saves\sync_queue.json` | `pendingChoices: []` (다 나갔다), `nextSeq: 3`, `baseRevision`·`playthroughId` 채워짐 |
| 1.5 | Workbench: `SELECT seq, episode_id, option_index FROM choice_history ORDER BY seq;` | 2행, seq 1·2 연속 |
| 1.6 | `SELECT username FROM users;` | `guest-…` 1행 (+ 기존 seed 가 있으면 그 행) |

## 2. 비행기 모드 — 완료 기준 ①

**터미널 ① 에서 서버를 끈다** (Ctrl+C). 플레이 계속 — **선택 5개** 커밋.

| # | 확인 | 기대 |
|---|---|---|
| 2.1 | 콘솔 | 저장 오류 없음. (오프라인은 조용하다 — `[계정] … 닿지 않는다` 정도만) |
| 2.2 | 게임 진행 | **막히지 않는다** — 서버 실패가 진행을 세우면 그 자체로 실패다 |
| 2.3 | `sync_queue.json` | `pendingChoices` 5건, seq 연속(3~7), `chosenAt` 이 UTC `Z` |
| 2.4 | `slot1.json` | 마지막 커밋 상태로 갱신돼 있다 (로컬이 진실) |

서버 재기동(터미널 ①) → **앱(플레이 모드) 재시작** → 타이틀까지만.

| # | 확인 | 기대 |
|---|---|---|
| 2.5 | 콘솔 | Start 의 잔여 큐 동기화: `[동기화] 완료 — … 선택 5건` |
| 2.6 | `choice_history` | 7행, seq 1~7 연속 — **완료 기준 ① 충족** |
| 2.7 | `sync_queue.json` | `pendingChoices: []`, `baseRevision` 증가 |

## 3. 강제 종료 후 재개 — 완료 기준 ②

서버를 **끈 채로** 선택 1개 커밋 → 에디터 플레이 모드를 **그대로 정지**(강제 종료에 해당) → 재진입 → 진행 시작.

| # | 확인 | 기대 |
|---|---|---|
| 3.1 | 콘솔 | `[진행] 재개 — qwer/<저장된 에피소드>` — 처음(EP01)이 아니라 저장 지점의 에피소드부터 |
| 3.2 | 스탯 | 재개 후 선택지 잠금/해금 상태가 저장 전과 일치 (스탯 보존, D-017) |
| 3.3 | `sync_queue.json` | 방금의 1건이 **살아 있다** — **완료 기준 ② 충족** |
| 3.4 | 서버 켜고 다음 커밋(또는 재시작) | 남은 큐까지 나가고 seq 여전히 연속 |

## 3-1. 새 게임 (5번)

세이브가 있는 상태에서 타이틀로 → **5번**.

| # | 확인 | 기대 |
|---|---|---|
| 3-1.1 | 콘솔 | `[저장] 새 게임 — 세이브·큐 초기화.` 그리고 `[진행] 챕터 시작 — qwer/EP01` (재개 로그 없음) |
| 3-1.2 | 첫 선택 커밋 후 콘솔 | `[동기화] 회차 생성 — playthroughId N+1` — **새 회차**, `sync_queue.json` 의 `nextSeq` 가 2, `baseRevision` 이 새 슬롯의 1 |
| 3-1.3 | Workbench: `SELECT id, ended_at FROM playthroughs;` | 회차 2행, 둘 다 `ended_at` NULL (이전 회차 종료는 뒤 M) |
| 3-1.4 | (오프라인에서 5번) 큐에 미전송이 있었다면 | `[저장] 새 게임 — 서버에 못 보낸 이력 N건을 버린다.` 경고 후 진행 — 버리는 것이 로그로 드러난다 |

## 4. 이벤트 경로와 흡수 (주의: 실물 에셋은 EventKey 가 전부 빈 문자열)

`qwer.progression.json` 은 EventKey 를 하나도 안 갖는다 — 그래서 이 에셋으로는
`EpisodeWatched` 가 **한 번도 안 나는 것이 정상**이다. §1~§3 내내 확인할 것:

| # | 확인 | 기대 |
|---|---|---|
| 4.1 | `sync_queue.json` 의 `pendingEvents` | **항상 `[]`** — 빈 EventKey 에 이벤트를 만들면 그게 버그다 |

**(선택) 이벤트 경로 끝까지 — `qwer-events` 로.** 서버 fixture `src/test/resources/content/qwer-events.progression.json` 은
같은 에피소드·대사 ID 에 EventKey 3개(MILESTONE_MIDPOINT·ENDING_A/B)를 단 별도 챕터(`qwer-events`)다 —
Yarn 노드가 같아서 Unity 프리플라이트도 통과한다.

1. 이 파일을 Unity `Assets/@Dialogue/ChapterProgression/` 에 복사, VNAppBootstrap 인스펙터의 챕터 에셋으로 교체.
2. **`$SAVE\saves\` 를 지운다** — 챕터가 다르므로(qwer ≠ qwer-events) 이전 세이브·큐와 섞지 않는다.
3. 서버에 **복사해 넣은 그 파일**을 §0(b)와 같이 `-InFile` 로 수입 (checksum — D-015).
4. EP03_01 까지 플레이 → `event_log` 1행. 재개 후 같은 에피소드 재완주 → 동기화 200 인데 `event_log` 여전히 1행
   (acceptedEvents 0 — D-011 흡수, 큐는 그래도 비워진다).
5. 끝나면 인스펙터를 원래 에셋으로 되돌린다 (교체 상태를 커밋하지 않는다).

| # | 시나리오 | 기대 |
|---|---|---|
| 4.2 | (선택) 에셋 자체를 한 바이트 고치고 플레이 (수입은 안 함) | 콘솔 경고 `일치하는 서버 버전이 없다` + 동기화만 선다(큐 보존) — 로컬 저장은 계속. 확인 후 되돌린다 (D-015 의 "조용히 틀리지 않는다") |

## 5. 이 문서가 검증하지 않는 것

- **409 충돌 해소** — `ConflictDetected` 는 로그만 남긴다. 두 기기 시나리오는 M8.
- **장면 중간 복원** — 재개는 에피소드 단위다 (D-017). 라인 단위는 뒤 M.
- **로그인 UI·계정 승격** — 게스트뿐 (D-016).

## 6. 결과

| 절 | 결과 | 비고 |
|---|---|---|
| §1 온라인 첫 플레이 | | |
| §2 비행기 모드 (기준 ①) | | |
| §3 강제 종료 재개 (기준 ②) | | |
| §3-1 새 게임 (5번) | | |
| §4 흡수·불일치 | | |

## 7. 커밋

Unity 레포: `feat: M7 저장 포트 — 로컬 우선 저장 + 서버 동기화 큐` (17파일 + .meta).
서버 레포: `test(M7): 검증 및 문서 갱신` (docs 만 — 서버 코드 변경 없음).
