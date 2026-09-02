# M8 검증 절차 — A. 서버 계약 (회차 멱등·갈래, 즐겨찾기, 413)

> PLAN §2.6. 결과는 §6 표에. 선행: M7 `검증됨`, D-018~D-022·D-024, 코드 A-1~A-8 (2026-09-02 반영, 32파일).
> **이번 절의 무대는 서버 레포다.** Unity 는 열지 않는다 — B 절(두 기기·갈라지기·즐겨찾기 왕복)은 F6 뒤에 따로 쓴다.
> 완료 기준(계획서 §7-A): 멱등 POST(둘 → 하나), 자식 먼저·부모 나중 → `forked_from_id` 닫힘, 즐겨찾기
> PUT·GET(메타)·DELETE(soft)·재PUT(부활), 413 경계, 목록 팬아웃 없음, 테스트 전부 PASSED(97 + 18 = **115**).

**M7 에서 쌓인 검증 환경 함정 넷 — 이번에도 그대로 적용된다:**

1. 래퍼는 `gradlew.bat` 이다 (`.cmd` 아님).
2. `bootRun` 은 Ctrl+C 로 안 죽는다 — 멈췄다고 믿기 전에 `Invoke-RestMethod http://localhost:8080/content/chapters` 로 확인하고,
   살아 있으면 `netstat -ano | findstr :8080` → `taskkill /PID <pid> /F`. 테스트(§0) 는 서버가 죽어 있어야 한다(포트가 아니라 DB 를 공유하므로 — `DbCleaner` 가 `game` 을 비운다).
3. DB 질의는 **이번 사용자·회차로 거른다** — `game` 에는 seed(회차 20·선택 200)와 M2~M7 검증의 행이 남아 있다.
4. 콘텐츠를 다시 수입할 일이 있으면 `-InFile` (재인코딩 금지, checksum).

**의도한 단절 하나 (D-019, C6)**: 이 코드가 뜬 서버에 **F6 전 Unity** 를 붙이면 회차 생성이 **400** 이다
(`clientPlaythroughId` 필수). 고장이 아니라 B-1 이 붙기 전의 예상된 상태다. 지금 Unity 로 4번·5번을 누르지 않는다.

창은 **터미널 ①**(bootRun), **터미널 ②**(PowerShell), **Workbench** 셋이다.

---

## 0. 서버 테스트 — 코드가 서는지부터 (A-8)

터미널 ① 에서 서버가 **죽어 있는지** 확인하고(함정 2):

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare
.\gradlew.bat cleanTest test
```

**115건 전부 PASSED** 기대 — M7 의 97건 + 신규 18건:

| 클래스 | 신규 | 무엇을 지키나 |
|---|---|---|
| `PlaythroughApiTest` | +7 (15) | 멱등 200, 형식 400 셋, 갈래 링크 즉시/되채우기, 요청의 서버 id 무시, 요약 forks, 목록 팬아웃 없음(2슬롯×3즐겨찾기 → 1행), 슬롯 없는 회차 |
| `BookmarkApiTest` | +8 (신설) | 201→200, 메타 목록/단건 스냅샷/UTC, 출처 회차 순서 독립, soft delete·멱등·부활, 404, 400 셋, 403, 413 |
| `SaveSlotApiTest` | +2 (14) | 시간 둘·완료 왕복 + 생략 시 0/false, **1,048,576 / +1** 경계 |
| `SaveHistoryApiTest` | +1 (14) | 300 선택 + 이벤트 2 한 요청 → 다음 장면 이어 붙임 ("바뀌지 않는 것") |
| `StatsApiTest` | 0 (단언 +1) | seed 는 `forks` 0 |

이번엔 처음으로 **Flyway 가 테스트 DB 에 V6 를 적용**한다. 첫 실행에서 `Migrating schema "game" to version "6 - forks and bookmarks"` 가
로그에 한 번 보이고, 그 뒤로는 `Schema "game" is up to date` 다. (baseline 오류가 나면 M6 계획서 §6 C1 — 재생성이지 baseline-on-migrate 가 아니다.)

빨간 것이 있으면 **여기서 멈춘다** — 아래 수동 절차는 테스트가 초록일 때만 뜻이 있다.

## 1. 기동과 준비 (터미널 ①·②)

```powershell
.\gradlew.bat bootRun     # 터미널 ①. Flyway 로그에 V6 적용 한 줄.
```

터미널 ②: M6-check §3.0 의 `Call-Api` 함수를 그대로 붙여넣고(헤더 파라미터 있는 버전), 사용자 하나를 새로 만든다 —
seed 사용자를 쓰지 않는 이유는 함정 3 이다. 이 사용자 밑의 행만 보면 된다.

```powershell
$BASE = "http://localhost:8080"
Call-Api POST "$BASE/users" '{"username":"m8","password":"secret-pw"}'
$login = Invoke-WebRequest -Method POST -Uri "$BASE/auth/login" -ContentType 'application/json' -Body '{"username":"m8","password":"secret-pw"}' -UseBasicParsing
$TOKEN   = ($login.Content | ConvertFrom-Json).token
$USER_ID = ($login.Content | ConvertFrom-Json).userId
$AUTH    = @{ Authorization = "Bearer $TOKEN" }
"USER_ID=$USER_ID"

# 클라 회차 id 셋 — Unity 는 Guid "N"(32 hex) 을 보낸다. 눈으로 구분되게 글자 하나를 32번.
$A = 'a' * 32;  $B = 'b' * 32;  $C = 'c' * 32
# 즐겨찾기가 가리킬 콘텐츠 버전 — DB 에 있는 것으로. (M7 에서 Unity 에셋을 수입했으면 qwer v2 가 있다.)
$CV = 2
```

Workbench: `SELECT chapter_id, version FROM game.chapter_contents;` — `$CV` 가 거기 있는 값인지 본다.

## 2. 멱등 회차 생성 — 둘을 보내도 하나 (D-019)

```powershell
Call-Api POST "$BASE/users/$USER_ID/playthroughs" "{""clientPlaythroughId"":""$A""}" $AUTH
Call-Api POST "$BASE/users/$USER_ID/playthroughs" "{""clientPlaythroughId"":""$A""}" $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 2.1 | 첫 응답 | **201**, `{"playthroughId":N,"clientPlaythroughId":"aaaa…"}` |
| 2.2 | 둘째 응답 | **200**, 같은 `playthroughId` — 본문이 한 글자까지 같다 |
| 2.3 | Workbench | `SELECT id, client_id, forked_from_id, forked_from_client_id FROM game.playthroughs WHERE user_id = <USER_ID>;` → **1행** |
| 2.4 | 본문 없이 | `Call-Api POST "$BASE/users/$USER_ID/playthroughs" $null $AUTH` → **400** (F6 전 클라가 맞는 답, C6) |

첫 응답의 N 을 적어 둔다: `$PA = <N>`.

## 3. 갈래 — 자식이 먼저, 부모가 나중 (D-020)

부모 `$B` 는 아직 서버에 없다. 자식 `$C` 가 `$B` 에서 갈라졌다고 먼저 올린다.

```powershell
Call-Api POST "$BASE/users/$USER_ID/playthroughs" "{""clientPlaythroughId"":""$C"",""forkedFrom"":{""clientPlaythroughId"":""$B"",""sceneIndex"":3}}" $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 3.1 | 응답 | 201 — **모르는 부모라도 거절하지 않는다** (완결성 > 정확함, 계획서 §3-3) |
| 3.2 | Workbench (2.3 질의) | `$C` 행: `forked_from_id` **NULL**, `forked_from_client_id` = bbbb… |
| 3.3 | `Call-Api GET "$BASE/users/$USER_ID/playthroughs" $null $AUTH` | `$C` 의 `forkedFrom` 에 `clientPlaythroughId`·`sceneIndex` 만 있고 **`playthroughId` 가 없다** |

이제 부모가 도착한다:

```powershell
Call-Api POST "$BASE/users/$USER_ID/playthroughs" "{""clientPlaythroughId"":""$B""}" $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 3.4 | Workbench (2.3 질의) | `$C` 행의 `forked_from_id` 가 **`$B` 의 id 로 닫혔다** — 자식은 다시 올라오지 않았다 |
| 3.5 | 목록 GET | `$C.forkedFrom.playthroughId` 가 생겼다. `$A`·`$B` 는 `forkedFrom` 자체가 없다 |
| 3.6 | `Call-Api GET "$BASE/users/$USER_ID/summary" $null $AUTH` | `playthroughs: 3, forks: 1` (뿌리 2 = 3 − 1) |

## 4. 즐겨찾기 — PUT·GET·DELETE·재PUT (D-021)

```powershell
$BM = "{""label"":""갈림길 앞"",""preview"":""왼쪽으로 갈까"",""chapterId"":""qwer"",""chapterVersion"":$CV,""playthroughClientId"":""$A"",""sceneIndex"":4,""createdAt"":""2026-09-02T21:00:00+09:00"",""snapshot"":{""sceneIndex"":4,""variables"":{""`$int"":7}}}"
Call-Api PUT "$BASE/users/$USER_ID/bookmarks/bm1" $BM $AUTH
Call-Api PUT "$BASE/users/$USER_ID/bookmarks/bm1" $BM $AUTH
Call-Api GET "$BASE/users/$USER_ID/bookmarks"     $null $AUTH
Call-Api GET "$BASE/users/$USER_ID/bookmarks/bm1" $null $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 4.1 | 첫 PUT | **201**, `{"clientBookmarkId":"bm1","playthroughId":<$PA>,"updatedAt":…}` — 출처 회차 `$A` 가 있으니 서버 id 가 바로 붙는다 |
| 4.2 | 둘째 PUT | **200**, 같은 본문(updatedAt 만 다를 수 있음). Workbench `SELECT COUNT(*) FROM game.bookmarks WHERE user_id = <USER_ID>;` → 1 |
| 4.3 | 목록 | 1건. `createdAt` 이 **`2026-09-02T12:00:00Z`** (+09:00 → UTC, D-009). **`snapshot` 키가 없다** |
| 4.4 | 단건 | 같은 메타 + `snapshot` 이 보낸 그대로 (`{"sceneIndex":4,"variables":{"$int":7}}`) |

```powershell
Call-Api DELETE "$BASE/users/$USER_ID/bookmarks/bm1" $null $AUTH
Call-Api DELETE "$BASE/users/$USER_ID/bookmarks/bm1" $null $AUTH
Call-Api GET    "$BASE/users/$USER_ID/bookmarks/bm1" $null $AUTH
Call-Api PUT    "$BASE/users/$USER_ID/bookmarks/bm1" $BM $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 4.5 | DELETE 둘 | **204, 204** — 두 번째도 실패가 아니다(멱등) |
| 4.6 | GET | **404** `NOT_FOUND`. Workbench: 행은 남아 있고 `deleted_at` 이 채워졌다 (soft) |
| 4.7 | 재PUT | **200** (행이 있었으므로 201 이 아니다), `deleted_at` 이 NULL 로 돌아왔다 — 부활 |
| 4.8 | 남의 것 | `Call-Api GET "$BASE/users/1/bookmarks" $null $AUTH` → **403** (경로 본인 확인, C4) |
| 4.9 | 없는 버전 | `$BM` 의 `chapterVersion` 을 99 로 바꿔 PUT → **404** |

## 5. 413 — 스냅샷 상한 (D-022)

경계값은 테스트(§0)가 잰다(1,048,576 통과 / +1 거절). 여기서는 문이 실제 HTTP 에서도 닫히는지만 본다.

```powershell
$BIG = '{"blob":"' + ('a' * 1100000) + '"}'
Call-Api PUT "$BASE/users/$USER_ID/bookmarks/bm-big" "{""label"":""big"",""chapterId"":""qwer"",""chapterVersion"":$CV,""sceneIndex"":0,""snapshot"":$BIG}" $AUTH
Call-Api PUT "$BASE/playthroughs/$PA/saves/1" "{""chapterId"":""qwer"",""chapterVersion"":$CV,""currentEpisodeId"":""EP01"",""snapshot"":$BIG,""playSeconds"":0,""baseRevision"":0}" $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 5.1 | 즐겨찾기 | **413** `{"code":"PAYLOAD_TOO_LARGE","message":"bookmark snapshot 이(가) 상한을 넘었습니다: 1100011 bytes > 1048576"}` |
| 5.2 | 세이브 | **413**, 같은 code. Workbench: `save_slots` 에 이 회차의 행이 **없다** — 413 은 아무것도 쓰지 않는다 |

## 6. 목록 확장 — 팬아웃 없음 (R4·R5, C3)

`$PA` 에 슬롯 둘을 올리고(둘째는 F6 전 형식 — 시간 둘·완료 없이), 즐겨찾기가 둘(bm1 + 하나 더) 인 상태에서 목록을 본다.

```powershell
$SNAP = '{"nodeName":"qwer_EP01","variables":{"$int":1}}'
Call-Api PUT "$BASE/playthroughs/$PA/saves/1" "{""chapterId"":""qwer"",""chapterVersion"":$CV,""currentEpisodeId"":""EP02_01"",""snapshot"":$SNAP,""playSeconds"":120,""baseRevision"":0,""inheritedPlaySeconds"":100,""ownPlaySeconds"":20,""chapterCompleted"":true}" $AUTH
Call-Api PUT "$BASE/playthroughs/$PA/saves/2" "{""chapterId"":""qwer"",""chapterVersion"":$CV,""currentEpisodeId"":""EP01"",""snapshot"":$SNAP,""playSeconds"":5,""baseRevision"":0}" $AUTH
Call-Api PUT "$BASE/users/$USER_ID/bookmarks/bm2" $BM $AUTH
Call-Api GET "$BASE/users/$USER_ID/playthroughs" $null $AUTH
Call-Api GET "$BASE/playthroughs/$PA/saves" $null $AUTH
```

| # | 확인 | 기대 |
|---|---|---|
| 6.1 | 회차 목록 | **3행** (`$A`·`$C`·`$B`, id 순) — 슬롯 2 × 즐겨찾기 2 로 곱해져 있지 않다 |
| 6.2 | `$A` 행 | `slotCount: 2, bookmarkCount: 2, chapterId: "qwer", chapterVersion: $CV, currentEpisodeId: "EP02_01", chapterCompleted: true, inheritedPlaySeconds: 100, ownPlaySeconds: 20, playSeconds: 120, lastSavedAt` 있음 — 전부 **슬롯 1** 의 값 |
| 6.3 | `$B`·`$C` 행 | `slotCount: 0, bookmarkCount: 0`, `chapterId` 이하 **키 없음**(슬롯이 없다), `lastSavedAt` 없음 |
| 6.4 | 슬롯 목록 | 슬롯 2 는 `inheritedPlaySeconds: 0, ownPlaySeconds: 0, chapterCompleted: false` — 안 보낸 값은 0/false (A-4, F6 전 호환) |

## 7. 이 문서가 검증하지 않는 것

- **F6 이후의 Unity** — 회차 생성 동승·PUT 확장·즐겨찾기 동기화·시작 시 모든 회차 큐(B-1~B-4). B 절.
- **두 기기 시나리오와 409 해소** — D-023 결정 뒤(B-5·B-6).
- **동시 POST 경쟁**(같은 클라 id 로 동시에 둘) — 코드는 `DuplicateKeyException` 을 잡아 진 쪽도 200 으로 접지만,
  MockMvc 로는 재현이 어렵고 수동으로도 어렵다. M4 의 `SaveSlotConcurrencyTest` 같은 스레드 테스트를 붙일지는 M9 점검에서.

## 8. 결과

| 절 | 결과 | 비고 |
|---|---|---|
| §0 서버 테스트 115건 | | |
| §2 멱등 회차 | | |
| §3 갈래 되채우기 | | |
| §4 즐겨찾기 | | |
| §5 413 | | |
| §6 목록 팬아웃 | | |

## 9. 커밋

서버 레포: `feat(M8-A): 회차 멱등·갈래, 즐겨찾기, 스냅샷 413 — V6` (32파일 + docs).
M7 의 `test(M7): 검증 및 문서 갱신` 이 아직 안 됐으면 먼저 따로 — 두 M 을 한 커밋에 섞지 않는다.
