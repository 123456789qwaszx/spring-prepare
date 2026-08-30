# spring-prepare

Spring 학습 레포. 실습1·2(메모장·도서 API) 위에, 실습3 — **ked VN 게임 서버**(콘텐츠 창고 + 세이브 금고)를
쌓아 올리고 있다. 실습3 이 이 레포의 본체다.

## 실행

```powershell
# 요구: JDK 17+, 로컬 MySQL 8.x (DB: game, game_test — 만드는 법은 docs/M6-check.md §1)
# 설정: src/main/resources/application-local.properties (gitignore — DB 접속, app.admin-key)
#       src/test/resources/application-test.properties  (gitignore — .example 을 복사해 채운다)
.\gradlew.bat bootRun          # 기동 시 Flyway 가 스키마를 맞춘다 (V1~V5)
.\gradlew.bat cleanTest test   # 반드시 cleanTest — 그냥 test 는 조용히 건너뛴다 (docs/README.md 규칙 8)
```

## API (M6 기준)

| 영역 | 엔드포인트 | 보호 |
|---|---|---|
| 가입 | `POST /users` | 공개 |
| 인증 | `POST /auth/login` → `{token, userId, expiresAt}` · `POST /auth/logout` | 공개 / Bearer |
| 사용자 | `GET /users/{id}` · `GET /users/{id}/summary` | Bearer, **본인만** |
| 회차 | `POST·GET /users/{id}/playthroughs` · `POST /playthroughs/{pid}/end` | Bearer, 본인 소유 |
| 세이브 | `PUT·GET /playthroughs/{pid}/saves[/{slotNo}]` · `…/choices` · `…/events` | Bearer, 본인 소유 |
| 콘텐츠 | `POST /content/chapters`·`/content/definition` | `X-Admin-Key` |
| 콘텐츠 | `GET /content/**` | 공개 (클라 다운로드) |
| 통계 | `GET /stats/events` · `GET /stats/chapters/{id}/choices` | `X-Admin-Key` |

세이브 업로드의 계약(baseRevision·409 두 종류·force·replayed)은 `docs/plans/M4.md` 와
인수인계 문서의 "API 형식"이 정본이다. 모든 에러는 `{code, message, …}` — `code` 로 분기한다.

## 마일스톤

| M | 내용 | 상태 |
|---|---|---|
| M0~M5 | 접속 → 콘텐츠 → 세이브 → 이력 → 멱등성·충돌 → 집계 | **검증됨·커밋** |
| M6 | 마감: Flyway·BCrypt·토큰·관리자 키·에러 형식 | 작성됨 — 검증 대기 |
| M7~M8 | Unity 저장 포트·충돌 UI | 대기 |
| M9 | 선택 과제 (JPA 비교 등) | 대기 |

## 문서

전부 `docs/` 에 있다 — **요약하지 않고 링크한다** (M6 계획서 §3-8):
[문서 지도와 운영 규칙](docs/README.md) · [정본 계획](docs/PLAN.md) · [상태 보드](docs/STATUS.md) ·
[결정 기록](docs/DECISIONS.md) · [분석](docs/ANALYSIS.md) · M별 계획 `docs/plans/M{n}.md` ·
검증 절차 `docs/M{n}-check.md`
