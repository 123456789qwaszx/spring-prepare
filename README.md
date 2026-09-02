# spring-prepare

Spring 학습 레포. 실습1·2(메모장·도서 API) 위에, 실습3 — **ked VN 게임 서버**(콘텐츠 창고 + 세이브 금고)를
쌓아 올리고 있다. 실습3 이 이 레포의 본체다.

## 처음이라면

**[`docs/RUNBOOK.md`](docs/RUNBOOK.md)** — 준비물 → DB 둘 → 설정 파일 둘 → 기동·테스트 → API 호출 도구(`scripts/api.ps1`) → 화면 → Unity → 함정 표. 외울 것 없이 한 시간.

```powershell
.\gradlew.bat bootRun          # 기동 시 Flyway 가 스키마를 맞춘다 (V1~V6)
.\gradlew.bat cleanTest test   # 반드시 cleanTest — 120건 (2026-09-02)
. .\scripts\api.ps1             # Ked-Connect / Ked-Login / Ked / Ked-Admin / Ked-Import / Test-Ked / Stop-Ked
```

설정 파일 둘은 gitignore 다 — `src/main/resources/application-local.properties`, `src/test/resources/application-test.properties`. 각각 `.example` 을 복사해 채운다.
관리자 화면: `http://localhost:8080/admin.html`.

## API (M9 기준)

| 영역 | 엔드포인트 | 보호 |
|---|---|---|
| 가입 | `POST /users` | 공개 |
| 인증 | `POST /auth/login` → `{token, userId, expiresAt}` · `POST /auth/logout` | 공개 / Bearer |
| 사용자 | `GET /users/{id}` · `GET /users/{id}/summary` | Bearer, **본인만** |
| 회차 | `POST /users/{id}/playthroughs` (멱등, `clientPlaythroughId`·`forkedFrom?`) · `GET …/playthroughs` · `POST /playthroughs/{pid}/end` | Bearer, 본인 소유 |
| 세이브 | `PUT·GET /playthroughs/{pid}/saves[/{slotNo}]` · `…/choices` · `…/events` | Bearer, 본인 소유 |
| 즐겨찾기 | `PUT·GET·DELETE /users/{id}/bookmarks[/{clientBookmarkId}]` | Bearer, 본인만 |
| 콘텐츠 | `POST /content/chapters`·`/content/definition` | `X-Admin-Key` |
| 콘텐츠 | `GET /content/**` | 공개 (클라 다운로드) |
| 통계 | `GET /stats/events` · `GET /stats/chapters/{id}/choices` · `GET /stats/chapters/{id}/overview` | `X-Admin-Key` |

클라와의 계약 정본은 [`docs/handoff/server-2026-09-02.md`](docs/handoff/server-2026-09-02.md). 세이브 업로드(baseRevision·409·force·replayed)는 `docs/plans/M4.md`.
모든 에러는 `{code, message, …}` — `code` 로 분기한다. 응답의 null 은 키가 있고 값이 null 이다(F46).

## 마일스톤 — 전부 검증됨 (2026-09-02, 서버 종료)

| M | 내용 | 상태 |
|---|---|---|
| M0~M5 | 접속 → 콘텐츠 → 세이브 → 이력 → 멱등성·충돌 → 집계 | 검증됨·커밋 |
| M6 | 마감: Flyway·BCrypt·토큰·관리자 키·에러 형식 | 검증됨·커밋 |
| M7 | Unity 저장 포트 (로컬 우선 + 동기화 큐) | 검증됨·커밋 |
| M8 | A 서버 — 멱등 회차·갈래·즐겨찾기·413 (V6) / B Unity — F6·복구·409 갈라지기 | A 검증됨·커밋 / B 작성됨(Unity 검증 중) |
| M9 | 마감 — 관리자 화면·챕터 개요·`ended_at` 결정·[회고](docs/RETRO.md) | 검증됨 |

## 문서

전부 `docs/` 에 있다 — **요약하지 않고 링크한다** (M6 계획서 §3-8):
[RUNBOOK](docs/RUNBOOK.md) · [문서 지도와 운영 규칙](docs/README.md) · [정본 계획](docs/PLAN.md) · [상태 보드](docs/STATUS.md) ·
[결정 기록](docs/DECISIONS.md) · [회고](docs/RETRO.md) · [분석](docs/ANALYSIS.md) · M별 계획 `docs/plans/M{n}.md` ·
검증 절차 `docs/M{n}-check.md` · 클라 계약 `docs/handoff/`
