# M9 검증 절차 — 관리자 화면 · 챕터 개요

> PLAN §2.6. 결과는 §4 표에. 선행: M8-A 검증됨, D-025~D-027, 코드 M9-1a~c (2026-09-02, 서버 7파일 + `static/admin.html`).
> 완료 기준(계획서 §7): 테스트 **120건**, 화면이 seed 숫자를 그대로 그린다, 키 없이 401 이 화면에 보인다.
> 함정은 M8-check 머리의 다섯 그대로 — 특히 **테스트 전에 서버가 죽어 있을 것**, `application-test.properties` 가 있을 것.

창은 **터미널 ①**(bootRun), **Workbench**, **브라우저** 셋이다. PowerShell 은 필요 없다.

---

## 0. 서버 테스트 (M9-1b)

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare
.\gradlew.bat cleanTest test
```

**120건 전부 PASSED** 기대 — M8 의 116건 + 신규 4건:

| 클래스 | 신규 | 무엇을 지키나 |
|---|---|---|
| `StatsApiTest` | +1 (11) | seed 개요 20/0/0.0/0/0 — 슬롯 둘인 회차 10개가 두 번 세어지면 30(팬아웃). 없는 버전 404, 키 없이 401. 요약 `completedPlaythroughs` 0 단언 추가 |
| `ChapterOverviewApiTest` | +3 (신설) | v1 = 2/1/**50.0**/1/1(완주는 슬롯 1 의 `chapter_completed`, 지운 즐겨찾기는 빠짐), v2 = 1/0/**0.0**/0/1(생략 = 최신), 요약 `completedPlaythroughs 1`·`endedPlaythroughs 0` |

## 1. 화면 — seed 로 대조 (M9-1c)

seed 를 `game` 에 넣는다 — M5-check §1 그대로 (Workbench 에서 `game` 을 기본 스키마로, `db/seed.sql` ⚡⚡). ⚠ M7·M8 검증 행이 지워진다(check 문서에 기록돼 있으니 잃을 것 없음).

터미널 ① `.\gradlew.bat bootRun` → 브라우저 `http://localhost:8080/admin.html`.

| # | 조작 | 기대 |
|---|---|---|
| 1.1 | 키 없이 열기 | 챕터 선택에 `qwer — 큐더블유이알 (최신 v1)` 이 찬다(공개 GET). 상태줄에 **`HTTP 401 UNAUTHORIZED — … — 관리자 키를 확인`**. 표는 빈 채 |
| 1.2 | 틀린 키 입력 → 적용 | 같은 401 — 화면이 해석하지 않고 서버 code 를 보인다 |
| 1.3 | 맞는 키(`application-local.properties`) → 적용 | 개요 카드 **20 · 0 · 0.0% · 0 · 0**. 선택 비율: EP01 `성실하게 간다 50 / 요령껏 간다 30 / 그냥 간다 20` (50.0/30.0/20.0%), EP02_01 `계속 걷는다 40` (100.0%), EP03_02 `왼쪽으로 36 / 오른쪽으로 24` (60.0/40.0%). 이벤트: MILESTONE_MIDPOINT 15/20 **75.0%**, ENDING_A 8 **40.0%**, ENDING_B 4 **20.0%**, 처음 `2026-08-02 01:30:00 UTC` |
| 1.4 | 탭 새로고침 | 키가 남아 있어 바로 그려진다(sessionStorage). 새 탭에서는 다시 401 — 의도한 것 |
| 1.5 | 버전 선택에 v1 만 | seed 는 v1 뿐. 버전을 바꿀 수 없으니 §2 로 |

숫자가 `StatsApiTest` 의 상수와 같으면 화면은 아무것도 계산하지 않은 것이다 — 그것이 검증 대상이다.

## 2. 화면 — 실제 데이터 (선택)

seed 대신 M7·M8 검증 데이터가 남아 있는 `game` 에서 열어 본다(seed 를 넣기 전에 먼저 해도 된다).

| # | 조작 | 기대 |
|---|---|---|
| 2.1 | 챕터 `qwer` → 버전 **v2** | 개요의 `회차` = Workbench `SELECT COUNT(DISTINCT playthrough_id) FROM game.save_slots s JOIN game.chapter_contents c ON c.id = s.chapter_content_id WHERE c.chapter_id='qwer' AND c.version=2 AND s.slot_no=1;` 과 같다. `끝낸 회차` 는 M8-check §6 에서 올린 회차 32 의 슬롯 1(`chapterCompleted: true`) 하나 이상. `갈래` 는 33 |
| 2.2 | v1 로 바꾸기 | 카드가 바뀐다 — 버전은 따로 센다 |

## 3. 이 문서가 검증하지 않는 것

- 사용자 조회·기간 필터 — 만들지 않았다(D-027).
- M9-4 JPA 조각 — 선택. 하면 `docs/M9-jpa.md` 에 따로.
- Unity M8-B — `ked-presentation-runtime/docs/m8b-check.md` 가 맡는다.

## 4. 결과

| 절 | 결과 | 비고 |
|---|---|---|
| §0 테스트 120건 | **통과** (09-02) | 120건 전부 PASSED |
| §1 화면 (seed) | **통과** (09-02) | 키 없이 401 → 키 적용 → 20/0/0.0/0/0 · 50/30/20 · 40 · 60/40 · 75/40/20 전부 `StatsApiTest` 상수와 일치. 새로고침 유지, 새 탭 401 |
| §2 화면 (실데이터) | 통과 (09-02) | 아미야 "아주 잘 되네" — 세부 숫자 미기록 |

## 5. 커밋

`feat(M9): 관리자 통계 화면 + 챕터 개요, D-025~D-027, 회고` — 서버 7파일 + `admin.html` + docs. 이 커밋으로 **실습3 종료.**
