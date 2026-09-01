# M7 코드 해설 — 목적·규칙·사고의 순서

> 대상: `ked-presentation-runtime` 의 M7 산출물 17파일 (2026-08-31). 검토하며 옆에 두는 문서.
> 정본 결정은 DECISIONS D-015~D-017, 작업표는 plans/M7.md §4.

---

## 0. 한 문장

**레포가 비워 둔 저장 포트를 채운다 — 로컬이 진실, 서버는 사본.** 진행 층은 건드리지 않고(순서를 두 곳에 적지 않는다) 커밋 사실만 밖으로 알린다.

이 문장에서 코드의 거의 전부가 따라 나온다. 아래는 그 "따라 나옴"을 순서대로 푼 것이다.

## 1. 층 — 무엇이 어디 있고 서로 무엇을 아는가

```
[진행 코어]  Ked.Progression            무엇이 참인가.  +Restore(신뢰), ResolvedOption.SourceIndex
     ▲ 안다
[호스트]     ProgressionLauncher         ★ 검증 경계 — 저장 파일 ↔ 콘텐츠 대조는 여기 한 번
             ProgressionDriver           어떤 순서로 부르나. IProgressionReporter 에 직접 보고
     │ 인터페이스(IProgressionReporter)로만 안다
     ▼
[저장 층]    Assets/Scripts/Save/ (Assembly-CSharp, 네임스페이스 없음)  받아 적는다.
             SaveCoordinator : IProgressionReporter
                 ├─ ISaveStore(LocalFileSaveStore) ── AtomicFile
                 ├─ SyncQueue ───────────────────── AtomicFile
                 └─ ServerSyncSaveStore ─┬─ GuestSession ─── ServerApi
                                         ├─ ChapterVersionResolver ─ ServerApi
                                         └─ (읽기) ISaveStore, SyncQueue
             SaveJson: 전부가 쓰는 직렬화 규약 한 곳
[조립]       VNAppBootstrap.CreateSaveCoordinator()   Unity 를 아는 곳은 여기와 얇은 두 군데뿐
```

의존 방향이 한쪽이다. **저장 층에서 진행 층을 아는 클래스는 `SaveCoordinator` 하나**이고, 드라이버는 저장 층을 `IProgressionReporter` 인터페이스로만 안다. 보고는 이벤트가 아니라 **직접 호출**이다 — 루프 안에서 "무엇이 언제 기록되는가"가 코드 순서 그대로 읽힌다.

## 2. 규칙 — 코드 전체를 관통하는 것

| # | 규칙 | 코드에서 어디에 박혀 있나 |
|---|---|---|
| R1 | **로컬 먼저, 서버는 나중** | `SaveCoordinator.ReportChoiceCommitted` — 저장 → 큐 → `Sync()` 순서. 서버는 시동만 걸고 기다리지 않는다 |
| R2 | **서버 실패는 진행을 막지 않는다** | `ServerApi` 는 던지지 않고 `ApiResult` / 오프라인은 정상 경로라 로그도 조용 / fire-and-forget 의 유일한 catch 는 `TrySyncAsync` 안. **로컬 IO 실패는 삼키지 않는다** — 드라이버 `RunAsync` 의 catch 로 올라가 정직하게 멈춘다 |
| R3 | **seq 는 클라가 매기고, 발급과 적재는 한 파일 쓰기** | `SyncQueue.EnqueueChoice` — `NextSeq++` 와 `Add` 가 같은 `Persist()` 에 실린다 (계획 C1) |
| R4 | **큐를 비우는 조건은 200 하나** | `ServerSyncSaveStore` 는 accepted\* 를 읽지 않는다. 큐는 append-only 라 배치는 현재 큐의 **접두사** → `Acknowledge` 는 그 길이만큼 `RemoveRange(0, n)`. 전송 중 쌓인 건 뒤에 남는다 |
| R5 | **서버는 스냅샷을 열지 않는다 → 모양은 클라 것** | PUT 의 `Snapshot = save` (LocalSaveFile 통째). 두 모양을 따로 두면 언젠가 갈린다 |
| R6 | **시각은 UTC 문자열, 이름은 camelCase, 데이터 키는 불변** | `SaveJson` — `ProcessDictionaryKeys=false`. `DateTime.UtcNow.ToString("o")` 만 시각을 만든다 |
| R7 | **검증은 경계에서 한 번, 그 뒤는 신뢰** | 저장 파일 ↔ 콘텐츠 대조는 `ProgressionLauncher.LaunchAsync` 한 곳(챕터·에피소드 존재). 통과하면 `Restore` 도 드라이버도 다시 묻지 않는다 — 기존 `RunChapterAsync` 가 `TryGetNode` 의 bool 을 안 보는 것과 같은 결 |
| R8 | **비동기는 느린 것에만, 경계에서 두 가지** | 파일은 동기(ISaveStore 결정), 네트워크만 async. 경계: 진행 루프는 기다리지 않음(`_ =`), 겹침은 `_syncing/_syncAgain` |
| R9 | **아는 정보는 버렸다가 재계산하지 않는다** | 원본 서수는 `ChapterTransition.Resolve` 가 이미 안다 → `ResolvedOption.SourceIndex` 로 실어 보낸다(드라이버 재스캔 없음). 런처가 찾은 `ChapterProgression` 은 드라이버에 그대로 넘긴다(id 로 재조회 없음) |
| R10 | **모르는 값은 조용히 채우지 않는다** | 버전 대조 실패 → 동기화 정지(아무 버전이나 안 보낸다). 저장된 에피소드 없음 → 새 게임(이어 가는 척 안 한다). 파일이 깨졌으면 역직렬화가 던진다 — "없음"으로 위장하지 않는다 |

## 3. 숙련자의 사고 — 순서가 곧 설계다

코드를 쓰기 전에 아래 순서로 답이 나와 있어야 하고, 실제로 이 순서로 나왔다. **순서를 바꾸면 결과물이 바뀐다** — 예컨대 4 를 코드 뒤로 미루면 데이터 모양(SyncQueueFile)이 두 번 바뀐다.

**1. 진실이 어디 있나.** 요구사항에 "오프라인"이 있다 → 진실은 로컬 파일. 이 한 결정이 나머지를 다 정한다: 서버 실패는 무시 가능해야 하고(R2), 그러려면 못 보낸 것을 쌓는 자리(큐)가 필요하고, 큐가 있으면 멱등 키(seq)가 필요하다. 반대로 진실을 서버에 뒀다면 큐가 아니라 "서버 응답 기다리기"가 됐을 것이다.

**2. 서버가 무엇을 요구하나 — 계약을 먼저 읽는다.** `PUT /playthroughs/{pid}/saves/{slot}` 하나가 데이터 모양을 거의 다 정한다: `baseRevision` 필수(신규 0) → 큐 파일에 revision 보관 / `choices` 증분+seq → 이벤트 append 큐 / `chapterVersion` 필수 → 어디서 오나?(→4) / 경로에 pid → 회차 보관. 주목할 것: **서버 API 가 클라 큐 모양을 정한 게 아니다.** M3 때 "클라의 자연스러운 큐(이벤트 append + 최신 스냅샷 하나)"가 API 를 정했고, 그래서 지금 맞아떨어진다. 계획서 §3-3 의 문장이 구현에서 실증된 자리다.

**3. 사실이 생기는 지점을 코드에서 찾는다.** 저장할 사실 = "선택이 커밋됐다", "EventKey 에피소드를 완주했다". 전자는 `_state = _state.Commit(...)` 한 줄, 후자는 대사 완료 직후. 여기서 원칙: **생산자(드라이버)는 최소로, 소비자가 무엇을 저장할지 정한다.** 보고 인자는 사실(무엇이 어디서)만 나르고 seq·시각·파일 모양은 저장 층이 정한다. 그리고 보고는 **이벤트가 아니라 직접 호출**이다 — 이벤트는 "누가 언제 구독했나"를 코드 밖으로 밀어내고, 직접 호출은 루프를 읽으면 순서가 그대로 보인다. 필요한 정보(원본 서수)가 코어에서 이미 계산됐다면 **그걸 실어 보내고 다시 찍지 않는다**(`SourceIndex`).

**4. 모르는 것을 나열하고 코드 전에 결정한다.** 계약을 읽으면 구멍이 보인다 — chapterVersion 은 어디서(D-015), 누구로 로그인(D-016), 어디까지 되돌리나(D-017). 셋 다 데이터 모양에 영향을 준다(예: D-017 이 라인 단위였다면 LocalSaveFile 에 Yarn 변수 3종이 들어갔다). 하나라도 "나중에"로 미루면 파일 형식 마이그레이션이 생긴다.

**5. 실패 모드를 정상 경로보다 먼저 쓴다 — 단, 실패마다 방어 코드를 두는 것과는 다르다.** 저장 코드의 품질은 "그때 무슨 일이 일어나나"에 한 줄 답이 있는가로 정해진다. 답이 "경계에서 이미 걸렀다"이면 그 자리엔 코드가 **없어야** 한다:
- 앱이 죽는 순간 → seq 와 큐가 어긋날 수 있나? 한 파일 쓰기(R3). 파일이 반 토막? 원자적 쓰기.
- 서버가 없는 순간 → `NetworkError`, 큐 보존, 로그 조용.
- 토큰이 죽은 순간(서버 재시작) → 401 → 재로그인 1회 → 재시도. 계정 자체가 사라짐 → 파일 지우고 다음에 새 게스트.
- 데이터가 바뀐 순간(챕터 개편) → 런처가 대조(R7). 에피소드가 없으면 새 게임. 통과했으면 `Restore` 는 챕터 정의에서 출발해 덮기만 한다.
- 이미 보낸 걸 또 보내는 순간 → 서버가 replayed/흡수로 200, 큐는 비운다(R4).
- 전송 중에 또 커밋되는 순간 → 접두사 배치(R4), 재진입 가드(R8).
- 우리가 원자적으로 쓴 파일이 깨진 순간 → 정상 상태가 아니다. 역직렬화가 던지게 둔다 — "없음"으로 위장하는 try/catch 는 원인을 숨긴다.

첫 초안은 이 목록의 절반을 **두 번** 방어했다 — 런처가 걸러야 할 것을 드라이버가 다시 `TryGetChapter/TryGetNode` 로 묻고, 코어가 또 던지고, 이미 계산된 서수를 다시 스캔하고(`IndexOfOption`), 파일 깨짐을 셋이 각자 삼켰다. 리뷰(아미야)가 짚은 것이 정확히 이것이다: *이미 보장된 사실을 뒤에서 계속 의심하고 있다.* 경계를 하나 세우면 나머지는 지워진다.

**6. 층을 "느린 것만 비동기"로 자른다.** 파일은 수 KB — 동기가 단순하고 Unity API 제약도 피한다(계획 권장과 다른 결정, 근거는 `ISaveStore` 주석). 네트워크만 async. 그리고 async 경계에서 진행 루프가 절대 기다리지 않게 한다 — 저장이 게임을 느리게 하면 그 자체가 R2 위반이다.

**7. 직렬화 규약을 한 곳에 못 박는다.** Newtonsoft 의 camelCase 리졸버가 딕셔너리 키까지 바꾸는 것, DateTime 자동 파싱 — 이런 건 **나중에 발견하면 데이터 마이그레이션**이 된다. 그래서 첫 파일을 쓰기 전에 `SaveJson` 이 있어야 한다.

**8. 조립은 한 곳, 각 클래스는 Unity 를 최소로 안다.** `persistentDataPath` 는 문자열로 주입(테스트가 임시 폴더를 꽂을 수 있다), `TextAsset.bytes` 는 생성자에서 한 번만. Unity 를 아는 자리는 Bootstrap, Debug.Log, UnityWebRequest 셋뿐이다.

**9. 검증할 수 없는 것을 문서에 적는다.** 409 해소(M8), 라인 단위 복원, 로그인 UI — "안 했다"가 아니라 "어디서 한다"까지. M7-check §5.

## 4. 읽는 순서 (데이터 → 이벤트 → 정책 → 유틸)

1. `Save/SaveData.cs` — 디스크에 눕는 네 모양. 이걸 먼저 보면 나머지는 "이 모양을 누가 채우나"다.
2. `Progression/ProgressionLauncher.cs` — ★ 검증 경계. 저장 파일이 콘텐츠와 만나는 유일한 자리.
3. `Progression/ProgressionDriver.cs` 의 `RunChapterAsync` — `ReportEpisodeWatched`·`ReportChoiceCommitted` 가 나는 정확한 줄, `picked.SourceIndex`.
4. `Save/SaveCoordinator.cs` — 보고를 받는 쪽. 저장 → 큐 → `Sync()` 순서가 R1 그 자체.
5. `Save/SyncQueue.cs` — R3·R4 의 구현. append-only 와 접두사 `Acknowledge`.
6. `Save/ServerSyncSaveStore.cs` — 정책 전부(토큰→회차→버전→PUT, 결과 다섯 갈래).
7. 나머지는 얇다: `ServerApi`(TCS 한 번), `GuestSession`(문 하나), `ChapterVersionResolver`(SHA-256 한 번), `AtomicFile`, `LocalFileSaveStore`, `SaveJson`, `ServerDtos`.
8. 코어 셋 — `ResolvedOption.SourceIndex`(+`ChapterTransition` 두 줄), `ProgressionState.Restore`. 마지막에 봐도 된다. 코어답게 정책이 없다.

## 5. 검토하며 짚을 만한 곳 — 의식적으로 좁게 간 자리

- **`File.Replace`** — 데스크톱 전제. 모바일 타깃이 생기면 그때 폴백을 넣는다(지금은 없다).
- **깨진 파일은 던진다** — `slot1.json`·`sync_queue.json`·`account.json` 이 JSON 으로 안 읽히면 그 자리에서 예외다. 우리가 원자적으로 쓴 파일이라 정상 상태가 아니고, 스키마가 바뀌는 경우(필드 추가·삭제)는 Newtonsoft 가 기본값으로 넘겨 던지지 않는다. 사용자에게 "세이브가 손상됐다"를 보여 줘야 하는 날이 오면 그 UI 가 곧 새 경계다.
- **playSeconds** — `realtimeSinceStartup` 차이. 일시정지·백그라운드를 안 뺀다. 통계용이라 감수했고, 정밀해야 하면 여기 한 줄만 바뀐다.
- **`GuestSession` 의 로그인 401 → 계정 파일 삭제** — 개발 중 DB 재생성에 맞춘 결정. 운영이라면 "버림"이 아니라 사용자에게 알리는 쪽이 맞다.
- **재개 직후 EventKey 재완주 → 이벤트 중복 보고** — 서버 흡수(D-011)에 기댄다. 클라에서 거를 수도 있지만, "서버가 진실을 정한다"는 M6 의 결과와 일관되게 클라는 정직하게 보고만 한다.
- **`optionIndex` = 원본 `NextOptions` 서수** — 화면의 걸러진 번호가 아니다. 서버는 `option_count` 로 범위를 본다. 콘텐츠 툴이 간선 순서를 바꾸면 과거 이력의 의미가 바뀌는 문제는 서버 설계(M3)부터 있던 것이고 여기서 새로 생긴 게 아니다.
- **슬롯 차원** — `SyncQueueFile` 은 슬롯 1 하나를 전제한다(seq·baseRevision 이 슬롯별인데 파일에 슬롯 차원이 없다). 슬롯 UI 가 생길 때 이 파일에 차원을 넣는 것이 첫 일이다 — 주석에 적어 뒀다.
- **비밀번호가 파일에** — 게스트 계약(D-016)이라 괜찮지만, 정식 계정이 생기면 토큰만 남긴다.
- **기기별 UUID 대신 seq** — F33 이 지적한 것(force 뒤 두 기기 이력 혼합). M7 은 서버 계약(seq)을 그대로 따랐고, 바꾼다면 서버부터다.

## 6. 정리 — 이번에 배운 것

- **"진실이 어디 있나"가 첫 질문이고, 그 답이 큐·멱등 키·실패 처리 전부를 정한다.** 오프라인 우선은 기능이 아니라 아키텍처다.
- **계약을 먼저 읽으면 데이터 모양이 나온다.** 그리고 잘 만든 서버 계약은 클라의 자연스러운 모양을 반영한 것이라, 옮겨 담는 계층이 필요 없다(PendingChoice 가 그대로 ChoiceUpload 다).
- **생산자 최소, 소비자 결정.** 드라이버에 이벤트 하나를 넣는 것과 저장 로직을 넣는 것은 다르다 — 전자는 드라이버가 저장을 몰라도 된다.
- **저장 코드는 실패 모드 목록이다.** 정상 경로는 한 줄이고, 나머지는 전부 "그때 무슨 일이"의 답이다.
- **검증 경계는 하나다.** 실패 모드마다 방어 코드를 두는 것은 목록을 코드로 옮긴 것이 아니라 같은 질문을 세 번 하는 것이다. 경계(런처)를 세우고 그 뒤는 신뢰하면, 드라이버와 코어에서 `Try`·fallback·재스캔이 통째로 사라진다 — 첫 초안과 리팩터링의 차이가 정확히 그것이었다.
