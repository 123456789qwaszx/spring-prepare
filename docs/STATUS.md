# 상태 보드

> 마지막 갱신: 2026-08-29 — **M0 검증됨.** M0~M9 계획서 1차 점검 완료.
> 상태 값: `대기` · `진행 중` · `작성됨`(파일 반영, 실행 검증 전) · `검증됨`(완료 기준 통과) · `보류`

## 마일스톤

| M | 이름 | 상태 | 계획서 | 검증 문서 | 비고 |
|---|---|---|---|---|---|
| M0 | 접속 확인 | **검증됨** (08-29) | [plans/M0.md](plans/M0.md) | [M0-check.md](M0-check.md) | 커밋만 남음. 확인된 사실은 M0.md §7-1 |
| M1 | 콘텐츠 수입·배포 | 대기 | [plans/M1.md](plans/M1.md) | — | 착수 시 PLAN#1 (JSON vs LONGTEXT) 결정. 샘플 JSON 확보 필요 |
| M2 | 회차·세이브 업로드/복구 | 대기 | [plans/M2.md](plans/M2.md) | — | 착수 시 PLAN#2 (슬롯 수) 결정 |
| M3 | 선택 이력·이벤트 로그 | 대기 | [plans/M3.md](plans/M3.md) | — | 시간대 D-008. 출발점은 M0 F8 |
| M4 | 멱등성과 충돌 | 대기 | [plans/M4.md](plans/M4.md) | — | 핵심 학습. 착수 시 PLAN#3 결정 |
| M5 | 조회와 집계 | 대기 | [plans/M5.md](plans/M5.md) | — | EXPLAIN 기록 → M5-explain.md |
| M6 | 마감 | 대기 | [plans/M6.md](plans/M6.md) | — | PLAN#4, #5 결정. Flyway 근거는 M0에서 확보 |
| M7 | Unity 저장 포트 | 대기 | [plans/M7.md](plans/M7.md) | — | Unity 레포 작업 |
| M8 | Unity 복구·충돌 UI | 대기 | [plans/M8.md](plans/M8.md) | — | Unity 레포 작업 |
| M9 | 선택 과제 | 대기 | [plans/M9.md](plans/M9.md) | — | |

## 지금

- 위치: **M0 종료 직전.** 완료 기준 7개 전부 통과 (M0-check.md §6), 자동 테스트 6/6.
- 남은 일: 아미야가 커밋 3개(환경 정정 / 문서 / M0 구현 + 이번 문서 갱신).
- 그다음: M1 착수 전 **PLAN#1 결정**과 **샘플 JSON 확보**.

## 다음 M을 시작하기 전에 필요한 것 (M1)

| # | 항목 | 누가 |
|---|---|---|
| 1 | PLAN#1 결정: `chapter_contents.body`를 `JSON`으로 둘 것인가 `LONGTEXT`로 바꿀 것인가 (권장: JSON) | 아미야 |
| 2 | `qwer.progression.json`을 `src/test/resources/content/`에 복사 — Unity 레포 `Assets/@Dialogue/ChapterProgression/` | 아미야 (로컬에 Unity 레포가 있으면 복사, 없으면 Claude가 프로젝트 동기화본에서 꺼내 씀) |
| 3 | `game.definition.json` 샘플 존재 여부 확인 — 없으면 M1은 챕터만 하고 definition은 뒤로 미룰지 결정 | 아미야 |

## 점검 이력

### 2026-08-29 — M0 종료 후 M0~M9 1차 점검

M0에서 확인된 사실(M0.md §7-1 F1~F8)을 뒤 M의 전제와 대조한 결과, **계획의 구조를 바꿀 만한 어긋남은 없었다.** 반영한 것:

| 문서 | 반영 |
|---|---|
| plans/M0.md | 작업 표·완료 기준 `검증됨`, §7-1 "확인된 사실 F1~F8" 신설 |
| plans/M1.md | 선행 조건 3건 해소 표시(M0 통과, `game_test`에 콘텐츠 테이블 이미 존재, `NamedParameterJdbcTemplate` 자동 등록 확인). Jackson 3 import를 소스 확인 결과로 확정. `DbCleaner` 확장 순서 명시 |
| plans/M3.md | 시간대 결정(D-008)의 출발점을 M0 F8로 명시 — "지금 DB는 KST를 담는 DATETIME" |
| plans/M6.md | Flyway(PLAN#5) 근거에 M0의 수동 2회 적용 경험 추가 |
| ANALYSIS.md | R1·R2·R3 종결, R4 현실화, R6(코드량 증가 시 무컴파일 작성 비용) 신설 |
| M0-check.md | §6 결과표 작성 |

바꾸지 않은 것과 이유:
- **M2·M4·M5·M7·M8·M9**: M0가 건드리지 않은 영역이라 갱신할 사실이 없다. 계획을 "손봐야 할 것 같아서" 손보지 않는다.
- **PLAN.md**: 정본은 그대로 둔다. M0는 계획대로 끝났고 계획 자체가 틀린 곳은 없었다.
- **D-004(M0부터 Service 계층·ErrorResponse)**: 실전에서 검증됨 — 400이 두 종류(`BAD_REQUEST` / `CONSTRAINT_VIOLATION`)로 갈리는 것이 실제로 유용했다. M6의 "에러 형식 통일"은 예상대로 누락 케이스 채우기만 남는다.

## 이력

- 2026-08-28: 분석(ANALYSIS.md), 결정 D-001~D-005, 계획서 M0~M9 작성. M0 구현.
- 2026-08-29: M0 실행 검증 통과(빌드·API·테스트 6/6). 문서 갱신, M0~M9 1차 점검.
