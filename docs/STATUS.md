# 상태 보드

> 마지막 갱신: 2026-08-28 — M0 계획서 작성, 구현 착수
> 상태 값: `대기` · `진행 중` · `작성됨`(파일 반영, 실행 검증 전) · `검증됨`(완료 기준 통과) · `보류`

## 마일스톤

| M | 이름 | 상태 | 계획서 | 검증 문서 | 비고 |
|---|---|---|---|---|---|
| M0 | 접속 확인 | 진행 중 | [plans/M0.md](plans/M0.md) | [M0-check.md](M0-check.md) | Boot 4.1.1 복귀 포함 |
| M1 | 콘텐츠 수입·배포 | 대기 | [plans/M1.md](plans/M1.md) | — | 착수 시 PLAN#1 (JSON vs LONGTEXT) 결정 |
| M2 | 회차·세이브 업로드/복구 | 대기 | [plans/M2.md](plans/M2.md) | — | 착수 시 PLAN#2 (슬롯 수) 결정 |
| M3 | 선택 이력·이벤트 로그 | 대기 | [plans/M3.md](plans/M3.md) | — | |
| M4 | 멱등성과 충돌 | 대기 | [plans/M4.md](plans/M4.md) | — | 핵심 학습. 착수 시 PLAN#3 결정 |
| M5 | 조회와 집계 | 대기 | [plans/M5.md](plans/M5.md) | — | EXPLAIN 기록 → M5-explain.md |
| M6 | 마감 | 대기 | [plans/M6.md](plans/M6.md) | — | PLAN#4, #5 결정 |
| M7 | Unity 저장 포트 | 대기 | [plans/M7.md](plans/M7.md) | — | Unity 레포 작업 |
| M8 | Unity 복구·충돌 UI | 대기 | [plans/M8.md](plans/M8.md) | — | Unity 레포 작업 |
| M9 | 선택 과제 | 대기 | [plans/M9.md](plans/M9.md) | — | |

## 지금

- 위치: **M0** — 작업 표는 `plans/M0.md` §4. M0-1~M0-4 `작성됨`, M0-0·M0-5·M0-6 `대기`(아미야).
- Claude가 반영한 것 (2026-08-28):
  - `build.gradle` Boot 4.1.1 복귀 + UTF-8 컴파일 옵션, `.gitignore`에 `application-test.properties`, `application.properties` 주석.
  - `common/` 4개, `user/` 6개, 테스트 2개(`support/DbCleaner`, `user/UserApiTest`), `src/test/resources/application-test.properties.example` (실제 파일은 아미야가 복사해 생성 — M0-check §0.3).
  - docs: ANALYSIS, DECISIONS(D-001~D-005), README, STATUS, plans/M0~M9, M0-check.
- 아미야가 확인할 것: `docs/M0-check.md` §0(DB 준비) → §2(bootRun) → §3(curl) → §4(test) → §6에 결과 기록. 컴파일 오류가 나면 메시지 전문을 Claude에게.

## 다음

- M0 완료 기준 통과 → `plans/M0.md`~`M9.md` 전체 점검 → M1 착수 전 PLAN#1 결정.

## 이력

- 2026-08-28: 분석(ANALYSIS.md), 결정 D-001~D-005, 계획서 M0~M9 작성. M0 구현 시작.
