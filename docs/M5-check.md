# M5 검증 절차 — 조회와 집계

> PLAN §2.6. 결과는 §6 표에. 선행: M4 `검증됨`.
> **이번 M은 스키마가 바뀐다** (`V3__stats_indexes.sql`) 그리고 **데이터가 통째로 갈린다** (`db/seed.sql`).

창은 **Workbench** 와 **터미널 ①**(gradle) 둘이다. M2~M4 와 달리 API 를 손으로 두드리는 단계가 거의 없다 —
**이 M 의 검증은 대부분 Workbench 에서 일어난다.** 집계는 앱이 아니라 SQL 이 하기 때문이다.

---

## 0. 순서가 이 M 의 핵심이다

```
seed 설계 → Workbench 검산 → 쿼리를 Workbench 에서 맞춤 → 앱에 붙임 → 테스트 → EXPLAIN
```

**앱을 먼저 만들지 않는다.** 숫자가 틀렸을 때 "쿼리가 틀렸나 Java 가 틀렸나" 를 헤매지 않으려면,
SQL 이 맞는 답을 낸다는 것을 먼저 못박아야 한다 (M5 계획서 §3-2).
그리고 테스트에 넣을 값은 **쿼리 결과가 아니라 seed 설계표**에서 가져온다 — 결과를 옮겨 적으면
테스트가 "틀린 값과 같다" 를 통과시킬 뿐이다.

---

## 1. seed 적용 — **Workbench**

> ⚠ **테이블을 전부 비운다.** M2~M4 의 수동 확인 데이터는 사라진다.
> 결과는 각 M 의 check 문서에 기록돼 있으므로 잃을 것이 없다.

1. 좌측 SCHEMAS 에서 **`game` 더블클릭** (굵게 = 기본 스키마)
2. 확인: 새 탭에 `SELECT DATABASE();` → `game`
3. `File ▸ Open SQL Script` → `db/seed.sql` → **⚡⚡** (Execute All, `Ctrl+Shift+Enter`)

> 번개 하나(⚡)는 커서 위치의 문장만 돈다. **두 개(⚡⚡)** 라야 전부 실행된다.
> `SOURCE` 는 mysql 명령줄 클라이언트의 명령이라 Workbench 에서는 동작하지 않는다.

`db/seed.sql` 에 스키마 이름이 없는 이유: 같은 파일이 `game` 과 `game_test` 양쪽에 쓰인다
(테스트가 `@Sql` 로 같은 파일을 돌린다). 대신 **지금 어느 DB 에 붙어 있나**가 중요해진다.

---

## 2. 검산 — **Workbench**

### 2.1 행 수

```sql
SELECT item, n, expected, IF(n = expected, 'OK', '### 다름 ###') AS result
FROM (
            SELECT 'users'            AS item, COUNT(*) AS n,   5 AS expected FROM users
  UNION ALL SELECT 'devices',                  COUNT(*),        5             FROM devices
  UNION ALL SELECT 'chapter_episodes',         COUNT(*),        8             FROM chapter_episodes
  UNION ALL SELECT 'playthroughs',             COUNT(*),       20             FROM playthroughs
  UNION ALL SELECT 'playthroughs(종료)',       COUNT(*),       12             FROM playthroughs WHERE ended_at IS NOT NULL
  UNION ALL SELECT 'save_slots',               COUNT(*),       30             FROM save_slots
  UNION ALL SELECT 'choice_history',           COUNT(*),      200             FROM choice_history
  UNION ALL SELECT 'event_log',                COUNT(*),       27             FROM event_log
) t;
```
`result` 가 전부 `OK`.

### 2.2 분포 — **여기가 진짜 검산이다**

```sql
SELECT episode_id, option_index, COUNT(*) AS n
FROM choice_history GROUP BY episode_id, option_index ORDER BY 1, 2;
```

| episode_id | option_index | n |
|---|---|---|
| EP01 | 0 / 1 / 2 | **50 / 30 / 20** |
| EP02_01 | 0 | 40 |
| EP03_02 | 0 / 1 | **36 / 24** |

```sql
SELECT event_key, COUNT(*) AS n FROM event_log GROUP BY event_key ORDER BY 1;
```
`ENDING_A` 8, `ENDING_B` 4, `MILESTONE_MIDPOINT` 15.

> **분포가 어긋나면 AUTO_INCREMENT 문제다.** seed 의 `CASE` 식이 회차 id 를 직접 가리키므로
> (`playthrough_id <= 10 THEN 0`), 회차가 1~20 이 아닌 번호로 들어가면 분포가 조용히 달라진다.
> `DELETE` 는 다음 번호를 되돌리지 않으므로 seed 앞부분의 `ALTER TABLE … AUTO_INCREMENT = 1` 이 그 일을 한다.
> **오류가 아니라 다른 숫자가 나온다** — 그래서 이 검산을 건너뛸 수 없다.

---

## 3. 쿼리를 Workbench 에서 맞춘다 — **Workbench**

`src/main/resources/sql/stats/` 의 세 파일을 연다. `:chapterId` → `'qwer'`, `:version` → `1`,
`:userId` → `1` 로 바꿔 실행한다 (앱은 named parameter 로 채우지만 Workbench 는 못 읽는다).

| 파일 | 기대 |
|---|---|
| `event_reach.sql` | MILESTONE **75.0**, ENDING_A **40.0**, ENDING_B **20.0** |
| `choice_ratio.sql` | 6행. EP01 50/30/20, EP03_02 60/40, **라벨이 번호에 맞게** |
| `user_summary.sql` (userId=1) | 회차 4, 종료 4, 슬롯 4, 선택 40, playSeconds **1000** |

`user_summary.sql` 은 `:userId` → `5` 로도 돌려 본다:
회차 4, **종료 0**, 슬롯 8, 선택 40, playSeconds **8140**.
(`7400` 이 나오면 슬롯 2 의 합을 빠뜨린 것이다.)

### 라벨을 특히 본다

| episodeId | optionIndex | choiceLabel |
|---|---|---|
| EP01 | 0 | **성실하게 간다** |
| EP01 | 1 | **요령껏 간다** |
| EP01 | 2 | **그냥 간다** |
| EP03_02 | 0 | **왼쪽으로** |
| EP03_02 | 1 | **오른쪽으로** |

`JSON_TABLE` 의 `FOR ORDINALITY` 는 **1부터** 세고 우리 `option_index` 는 **0부터**다.
그래서 쿼리에 `option_ordinal - 1` 이 있다. 이걸 빠뜨리면 **라벨만 한 칸 밀리고 숫자는 전부 멀쩡하다** —
조용히 틀리는 종류라, seed 로 답을 미리 아는 것이 유일한 방어다.

---

## 4. 자동 테스트 — **터미널 ①**

먼저 `game_test` 에도 seed 가 필요한가? **아니다.** 테스트가 `@Sql` 로 매번 직접 돌린다.
다만 `game_test` 에 **스키마는 있어야** 한다 (테이블 9개).

```powershell
cd C:\Users\river\Documents\GitHub\spring-prepare
.\gradlew.bat compileTestJava
.\gradlew.bat cleanTest test
```

기대: **78건** (M0 6 + M1 23 + M2 18 + M3 13 + M4 9 + **M5 8** + `contextLoads` 1).

| 메시지 | 원인 | 대응 |
|---|---|---|
| 라벨이 `?꽦?떎?븯寃?` 같은 글자로 | `@SqlConfig` 의 encoding 기본값이 **플랫폼 기본**(한국어 Windows = MS949) | `@SqlConfig(encoding = "UTF-8")`. 숫자 테스트는 전부 통과하므로 라벨 단언이 없으면 못 잡는다 |
| 다른 테스트의 `DbCleaner` 가 오류 **1175** (safe update mode) | seed 가 `SET SQL_SAFE_UPDATES = 1` 로 끝나 **커넥션 풀이 오염됨** | seed 는 되돌리지 않는다 (§아래) |
| 라벨이 한 칸씩 밀림 | `option_ordinal - 1` 누락 | 쿼리 확인 |
| `playSeconds` 가 7400 | 슬롯 2 를 안 셌다 | 스칼라 서브쿼리 확인 |
| `Cannot resolve … db/seed.sql` | 작업 디렉터리가 프로젝트 루트가 아니다 | Gradle 로 돌린다 (IDE 단독 실행 시 주의) |

> **세션 변수는 커넥션에 붙어 있다.** seed 가 `SET SQL_SAFE_UPDATES = 1` 로 끝나면 그 커넥션이
> safe mode 가 켜진 채 Hikari 풀로 돌아가고, 다음에 그것을 빌린 테스트의 `DbCleaner` 가
> WHERE 없는 DELETE 로 죽는다. 트랜잭션이 끝나도 세션 변수는 안 사라진다.
>
> "정리했으니 원상복구" 는 **원래 값을 알 때만** 옳다. 이 파일은 두 환경에서 돌고
> 원래 값이 다르다(Workbench 1, 앱 0). 그래서 **0 으로만 맞추고 되돌리지 않는다** —
> 양쪽 모두에 안전한 방향으로만 바꾼다.

---

## 5. EXPLAIN 전 → 인덱스 → EXPLAIN 후

**순서가 중요하다.** V3 를 적용하기 **전에** 먼저 찍는다. 같은 자로 두 번 재야 비교가 된다.

### 5.1 전 — **Workbench** (`game`)

```sql
EXPLAIN
SELECT e.event_key, COUNT(DISTINCT e.playthrough_id),
       (SELECT COUNT(*) FROM playthroughs),
       MIN(e.occurred_at), MAX(e.occurred_at)
FROM event_log e GROUP BY e.event_key ORDER BY 2 DESC, e.event_key;

EXPLAIN
SELECT ch.episode_id, ch.option_index, COUNT(*)
FROM choice_history ch
JOIN chapter_contents c ON c.id = ch.chapter_content_id
WHERE c.chapter_id = 'qwer' AND c.version = 1
GROUP BY ch.episode_id, ch.option_index;

EXPLAIN
SELECT u.id, COUNT(DISTINCT p.id), COUNT(DISTINCT s.id)
FROM users u
LEFT JOIN playthroughs p ON p.user_id = u.id
LEFT JOIN save_slots   s ON s.playthrough_id = p.id
WHERE u.id = 1 GROUP BY u.id;
```

`type` · `key` · `rows` · **`Extra`** 를 적어 둔다.

### 5.2 인덱스 적용 — **Workbench**, `game` 과 `game_test` **양쪽**

`db/migrations/V3__stats_indexes.sql` 을 열고, 기본 스키마를 `game` 으로 두고 ⚡⚡.
그다음 `game_test` 로 바꿔 다시 ⚡⚡.

스키마 전환이 번거로우면 스키마를 박아서 붙여넣어도 된다:
```sql
ALTER TABLE game.event_log      ADD  INDEX ix_event_stats (event_key, playthrough_id, occurred_at);
ALTER TABLE game.event_log      DROP INDEX ix_event_key;
ALTER TABLE game.choice_history ADD  INDEX ix_choice_stats (chapter_content_id, episode_id, option_index);

ALTER TABLE game_test.event_log      ADD  INDEX ix_event_stats (event_key, playthrough_id, occurred_at);
ALTER TABLE game_test.event_log      DROP INDEX ix_event_key;
ALTER TABLE game_test.choice_history ADD  INDEX ix_choice_stats (chapter_content_id, episode_id, option_index);
```

> **두 번 돌리면 실패한다.** `1061 Duplicate key name` 또는 `1091 Can't DROP`.
> 버그가 아니라 마이그레이션의 정의다 — 한 번만 적용되는 것이 마이그레이션이다.
> **"어디까지 적용했나" 를 사람이 기억해야 한다**는 것이 M6 Flyway 의 이유고,
> `flyway_schema_history` 가 그 기억을 대신한다.
>
> `game` 에만 적용하고 `game_test` 를 빼먹으면 **테스트만 다른 실행 계획을 탄다** —
> M1 에서 겪은 스키마 드리프트(R4)와 같은 종류다.

확인:
```sql
SHOW INDEX FROM game.event_log;        -- ix_event_stats 있고, ix_event_key 없어야 한다
SHOW INDEX FROM game_test.event_log;
SHOW INDEX FROM game.choice_history;   -- ix_choice_stats 있고, fk_choice_episode 도 그대로
```

### 5.3 후 — 5.1 과 **똑같은 세 쿼리**를 다시

결과는 `docs/M5-explain.md` 에 전/후를 나란히 적는다.

---

## 6. 결과 기록

**전부 통과 — 2026-08-30.**

| 항목 | 기대 | 결과 | 비고 |
|---|---|---|---|
| §1 seed 적용 | 오류 없이 끝 | ✅ | |
| §2.1 행 수 | 8개 항목 전부 OK | ✅ | |
| §2.2 분포 | 50/30/20, 40, 36/24 · 이벤트 8/4/15 | ✅ | AUTO_INCREMENT 재설정이 동작했다는 증거 |
| §3 event_reach | 75.0 / 40.0 / 20.0 | ✅ | |
| §3 choice_ratio | 6행, 라벨이 번호에 맞음 | ✅ | `option_ordinal - 1` 이 맞았다 |
| §3 user_summary | id 1 → 4/4/4/40/1000 | ✅ | |
| §4 자동 테스트 | **78건** | ✅ | 첫 시도 2건 실패 → §7 |
| §5 EXPLAIN 전/후 | `docs/M5-explain.md` | ✅ | ② 가 `ALL` → `ref` + `Using index` |

## 7. 부수적으로 확인된 것

- **첫 테스트 실행에서 7건이 깨졌고, 둘 다 `seed.sql` 이 원인이었다.**

  **(a) 커넥션 풀 오염** — seed 끝의 `SET SQL_SAFE_UPDATES = 1;` 이 그 커넥션을 오염시켜,
  전혀 무관한 `UserApiTest` 6건이 `DbCleaner` 의 DELETE 에서 오류 1175 로 죽었다.
  세션 변수는 트랜잭션이 아니라 **커넥션**에 붙어 있고, 풀은 그것을 되돌려 주지 않는다.
  M2 F22 에서 "safe update mode 는 Workbench 의 클라이언트 설정이라 앱은 무관" 이라고 적었는데,
  그 사실의 **이면**을 여기서 만났다 — 앱 커넥션에 그 설정을 심을 수도 있다는 것.

  **(b) seed 인코딩** — `@SqlConfig` 의 encoding 기본값이 플랫폼 기본(MS949)이라
  UTF-8 seed 를 잘못 읽어 `성실하게 간다` 가 `?꽦?떎?븯寃?` 로 들어갔다.
  **SQL 문법도 행 수도 멀쩡해서 숫자를 보는 테스트는 전부 통과했다.**
  라벨을 단언하는 테스트 하나만 깨졌다 — M3 의 PowerShell 한글 깨짐과 같은 종류로,
  "서버가 깨뜨린 게 아니라 읽는 쪽이 잘못 해석한 것" 이다.

  라벨 단언은 원래 `option_ordinal - 1` 을 지키려고 넣은 것인데, 인코딩 사고까지 잡았다.
  **한 테스트가 두 가지를 지킨 셈이고, 둘 다 "숫자는 멀쩡한데 조용히 틀린" 종류다.**

- **인덱스를 추가했는데 `rows` 가 하나도 안 줄었다** (②: 200 → 200). 그런데 `type` 은 `ALL → ref`,
  `Extra` 는 `Using where → Using index`, 그리고 `Using temporary` 가 사라졌다.
  **`rows` 만 보고 인덱스를 평가하면 이 개선을 통째로 놓친다.**

- **`user_summary` 가 쓰는 인덱스 셋이 전부 PK·FK·UNIQUE 다.** 조회를 노리고 만든 것이 하나도 없다.
  M2 에서 "UNIQUE 만이 확실한 방어선" 이라며 건 `uk_save_slot` 이 M5 에서 조회 인덱스 노릇을 한다.
  **제약을 제대로 걸면 인덱스는 덤으로 따라온다.**

- **`SOURCE db/seed.sql` 은 Workbench 에서 동작하지 않는다** — mysql 명령줄 클라이언트의 명령이다.
  (초안 주석에 그렇게 적어 두었다가 고쳤다.)
