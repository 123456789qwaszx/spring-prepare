# M0 검증 절차 — 접속 확인

> PLAN §2.6: 완료 기준을 curl/Postman 으로 재현하는 문서. 위에서 아래로 그대로 따라가면 M0 완료 기준을 전부 확인한다.
> 결과는 맨 아래 §6 표에 적고, `docs/plans/M0.md` §4·§7 을 갱신한다.

---

## 0. DB 준비 (한 번만)

### 0.1 `game` 에 스키마 적용 여부 확인

Workbench 에서:

```sql
USE game;
SHOW TABLES;
```

`users, devices, game_definitions, chapter_contents, chapter_episodes, playthroughs, save_slots, choice_history, event_log` 아홉 개가 보이면 적용된 것. 없으면 `docs/schema.sql` 을 열어 전체 실행.

### 0.2 테스트용 `game_test` 만들기 (D-002)

`schema.sql` 은 첫 두 문장이 `CREATE DATABASE IF NOT EXISTS game … ; USE game;` 이다. 이 두 곳의 `game` 을 `game_test` 로 바꿔 실행한다. 방법 둘 중 하나:

- Workbench: `schema.sql` 을 열어 위 두 줄만 `game_test` 로 고친 뒤 전체 실행. (파일은 저장하지 않는다 — 정본은 `game` 기준.)
- PowerShell (mysql CLI 가 PATH 에 있을 때):
  ```powershell
  (Get-Content docs\schema.sql -Encoding UTF8) -replace '\bgame\b','game_test' | mysql -u root -p
  ```

확인:

```sql
USE game_test;
SHOW TABLES;   -- 아홉 개
```

### 0.3 테스트 프로필 파일

`src/main/resources/application-local.properties` 를 `src/test/resources/application-test.properties` 로 **복사**하고 url 의 `game` 을 `game_test` 로 바꾼다 (접속 정보가 담긴 파일이라 Claude 가 대신 만들지 않았다. `.example` 참고). **url 이 `game_test` 인지 반드시 확인** — `game` 이면 테스트가 개발 데이터를 지운다. 이 파일은 `.gitignore` 되어 있다.

---

## 1. 앱 없이 DB 에서 먼저 본다 (M0 의 진짜 목표)

Workbench, `USE game;` 상태에서:

```sql
INSERT INTO users (username, password) VALUES ('probe', 'x');
INSERT INTO users (username, password) VALUES ('probe', 'x');   -- 두 번째
```

두 번째에서 `Error Code: 1062. Duplicate entry 'probe' for key 'users.uk_users_username'` 이 난다. **이 1062 가 앱에서 409 로 번역될 에러다.** 앱은 "이미 있는지" 미리 SELECT 하지 않는다 — 이 UNIQUE 가 방어선이다.

```sql
INSERT INTO users (username, password) VALUES (REPEAT('a', 31), 'x');
```

`Error Code: 1406. Data too long for column 'username'` — 이것이 앱에서 400 `CONSTRAINT_VIOLATION` 이 될 에러다.

```sql
SELECT id, username, created_at FROM users;
DELETE FROM users WHERE username = 'probe';
```

`created_at` 에 DB 시각이 들어간 것을 본다. `SELECT NOW(), @@global.time_zone, @@session.time_zone;` 도 한 번 보아 둔다 (M3 에서 쓴다).

---

## 2. 서버 실행

IntelliJ 터미널 또는 PowerShell, 프로젝트 루트에서:

```powershell
.\gradlew.bat bootRun
```

확인할 것:
- 배너에 `Spring Boot :: (v4.1.1)`.
- 로그에 `Tomcat started on port 8080`.
- 로그에 `The following 1 profile is active: "local"`.

**첫 빌드에서 컴파일 오류가 나면** 에러 메시지 전체(파일명·줄번호 포함)를 Claude 에게 붙여 넣는다. Claude 는 이 코드를 컴파일하지 못한 채 작성했다 (ANALYSIS §1.4).

---

## 3. API 시나리오

아래는 **Git Bash / WSL** 의 curl 기준. PowerShell 은 §3.5, Postman 은 §3.6.

### 3.1 생성 → 201

```bash
curl -i -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"username":"amiya","password":"test-only"}'
```

기대:
```
HTTP/1.1 201
Location: /users/1
Content-Type: application/json

{"id":1,"username":"amiya"}
```
(`id` 는 DB 가 준 값이라 1 이 아닐 수 있다.)

Workbench: `SELECT * FROM game.users;` → 행이 보이고 `created_at` 이 지금 시각.

### 3.2 같은 username 재요청 → 409

같은 명령을 다시 실행.

기대:
```
HTTP/1.1 409
{"code":"DUPLICATE","message":"이미 존재하는 값입니다."}
```
500 이 아니어야 한다. 500 이면 `GlobalExceptionHandler` 가 안 잡힌 것 (plans/M0.md §6 C4).

### 3.3 없는 id → 404

```bash
curl -i http://localhost:8080/users/999999
```

기대: `HTTP/1.1 404`, `{"code":"NOT_FOUND","message":"사용자가 없습니다: id=999999"}`

### 3.4 있는 id → 200, password 없음

```bash
curl -i http://localhost:8080/users/1     # 3.1 의 Location 값
```

기대: `200`, `{"id":1,"username":"amiya"}` — `password` 키가 없어야 한다.

### 3.5 400 두 가지 — 앱이 거른 것 vs DB 가 막은 것

```bash
curl -i -X POST http://localhost:8080/users -H "Content-Type: application/json" \
  -d '{"username":"   ","password":"x"}'
# → 400 {"code":"BAD_REQUEST", ...}          앱(UserService)이 거름

curl -i -X POST http://localhost:8080/users -H "Content-Type: application/json" \
  -d '{"username":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","password":"x"}'   # 31자
# → 400 {"code":"CONSTRAINT_VIOLATION", "detail":"MysqlDataTruncation"}   DB(VARCHAR(30))가 막음
```

두 400 의 `code` 가 다르다 — 어느 층이 일했는지 응답만 보고 안다.

### 3.6 PowerShell 로 할 때

PowerShell 5 의 `curl` 은 `Invoke-WebRequest` 별칭이다. `curl.exe` 를 쓰거나 아래처럼:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/users -ContentType 'application/json' `
  -Body '{"username":"amiya","password":"test-only"}'
```

상태 코드까지 보려면 `Invoke-WebRequest` + `.StatusCode`, 4xx 는 예외로 떨어지므로 `try { } catch { $_.Exception.Response.StatusCode }`.

### 3.7 Postman 으로 할 때

- Collection `spring-prepare / M0` 에 요청 5개 (3.1~3.5). Body 는 raw / JSON.
- 3.2 는 3.1 을 그대로 두 번 Send.

---

## 4. 자동 테스트

```powershell
.\gradlew.bat test --tests "com.sparta.springprepare.user.UserApiTest"
```

기대: 6 passed. 결과는 `build/reports/tests/test/index.html`.

실패하면 콘솔의 스택트레이스 전체를 Claude 에게. 자주 나는 것:
- `Failed to configure a DataSource` → §0.3.
- `Unknown database 'game_test'` → §0.2.
- `Table 'game_test.users' doesn't exist` → §0.2 에서 `USE game_test` 가 안 바뀐 것.
- 30자 테스트만 실패 → `SELECT @@sql_mode;` 에 `STRICT_TRANS_TABLES` 가 없는 것 (plans/M0.md §6).

전체 테스트(`.\gradlew.bat test`)는 기존 `contextLoads` 도 돌린다 — 그것은 `local` 프로필로 뜨므로 `application-local.properties` 가 있어야 한다 (ANALYSIS §3.7).

---

## 5. 정리

```sql
DELETE FROM game.users;   -- 수동 확인으로 넣은 행. M1 부터는 DbCleaner 가 game_test 만 지운다.
```

---

## 6. 결과 기록

| 항목 | 기대 | 결과 | 날짜 | 비고 |
|---|---|---|---|---|
| §2 bootRun | 4.1.1 배너, 8080 | ✅ | 2026-08-29 | 첫 빌드에 컴파일 오류 없었음. D-001 복귀 성공 |
| §3.1 POST | 201 + Location + DB 행 | ✅ | 2026-08-29 | `Invoke-WebRequest`로 alice(1), bob(2). `created_at` 14:37/14:38 KST 정상 |
| §3.2 재요청 | 409 DUPLICATE | ✅ | 2026-08-29 | 자동 테스트로 확인 (수동 curl 미실행) |
| §3.3 없는 id | 404 NOT_FOUND | ✅ | 2026-08-29 | 〃 |
| §3.4 있는 id | 200, password 없음 | ✅ | 2026-08-29 | 〃 |
| §3.5 공백 | 400 BAD_REQUEST | ✅ | 2026-08-29 | 〃 |
| §3.5 31자 | 400 CONSTRAINT_VIOLATION | ✅ | 2026-08-29 | 〃. 통과했다는 것은 `sql_mode`에 `STRICT_TRANS_TABLES`가 켜져 있다는 뜻 |
| §4 test | 6 passed | ✅ | 2026-08-29 | `game_test` 프로필로 6/6 |

부수적으로 확인된 것:
- `id`가 1, 2 → §1의 수동 probe INSERT는 건너뜀. 1062/1406 에러를 눈으로 보는 단계는 미실행이나, 자동 테스트가 같은 경로를 덮는다.
- `password`가 평문(`1234`, `5678`)으로 저장됨 — 의도된 상태. M6에서 BCrypt로 전환.
- 전체 `.\gradlew.bat test`(= `contextLoads` 포함)는 아직 미실행. `local` 프로필로 뜨므로 `application-local.properties`가 있는 이 PC에서는 통과할 것 (ANALYSIS §3.7).
