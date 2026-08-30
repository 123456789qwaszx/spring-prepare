# M5 EXPLAIN 기록 — 인덱스 전/후

> 측정: 2026-08-30, `game` (seed 적용 상태: 회차 20, 선택 200, 이벤트 27).
> 인덱스는 `db/migrations/V3__stats_indexes.sql`.

---

## 0. 무엇을 보는가

더미 데이터가 200행이라 **시간 차이는 의미가 없다.** 200행은 어떻게 읽어도 순식간이다.
그래서 보는 것은 네 열이다.

| 열 | 뜻 | 좋아지는 방향 |
|---|---|---|
| `type` | 행을 찾는 방식 | `ALL`(전체 스캔) → `index` → `range` → `ref` → `const` |
| `key` | 실제로 쓴 인덱스 | `NULL` 이면 안 쓴 것 |
| `rows` | 옵티마이저가 **읽을 것으로 추정**한 행 수 | 적을수록 |
| `Extra` | 부가 작업 | `Using index` 는 좋고, `Using temporary`·`Using filesort` 는 나쁘다 |

`Extra` 가 가장 정보량이 많다.

- **`Using index`** — 인덱스만 읽고 **테이블 본체를 아예 안 봤다**(covering index).
  이건 행이 200개든 2억 개든 참인 사실이라, 작은 더미에서도 의미가 있다.
- **`Using temporary`** — 묶으려고 임시 테이블을 만들었다.
- **`Using filesort`** — 정렬하려고 별도 작업을 했다. (이름과 달리 꼭 디스크를 쓰는 건 아니다.)

**`rows` 가 줄지 않아도 좋아질 수 있다** — 아래 ② 가 그 사례다.

---

## 1. 이벤트 도달률 (`sql/stats/event_reach.sql`)

```sql
SELECT e.event_key, COUNT(DISTINCT e.playthrough_id),
       (SELECT COUNT(*) FROM playthroughs),
       MIN(e.occurred_at), MAX(e.occurred_at)
FROM event_log e GROUP BY e.event_key ORDER BY 2 DESC, e.event_key;
```

### 전

| id | table | type | possible_keys | key | key_len | rows | Extra |
|---|---|---|---|---|---|---|---|
| 1 | `e` | index | uk_event_once, **ix_event_key** | `ix_event_key` | 202 | 27 | **Using temporary; Using filesort** |
| 2 | `playthroughs` | index | | `fk_playthroughs_user` | 8 | 20 | Using index |

### 후

| id | table | type | possible_keys | key | key_len | rows | Extra |
|---|---|---|---|---|---|---|---|
| 1 | `e` | index | uk_event_once, **ix_event_stats** | `ix_event_stats` | **215** | 27 | **Using index**; Using temporary; Using filesort |
| 2 | `playthroughs` | index | | `fk_playthroughs_user` | 8 | 20 | Using index |

### 읽기

**`Using index` 가 붙었다.** 전에는 `ix_event_key` 로 `event_key` 만 훑고, `playthrough_id`·`occurred_at`
을 얻으려고 행마다 테이블을 다시 찾아갔다. 이제 세 컬럼이 전부 인덱스에 있으니 **테이블을 열지 않는다.**

`key_len` 이 그 증거다:

```
202  event_key      VARCHAR(50) utf8mb4  = 50 × 4 + 2(길이 바이트)
  8  playthrough_id BIGINT
  5  occurred_at    DATETIME
───
215
```

**`Using temporary; Using filesort` 는 남았다.** 이건 인덱스로 못 없앤다:

- `COUNT(DISTINCT playthrough_id)` — 중복 제거에 임시 공간이 필요하다.
- `ORDER BY 2 DESC` — **집계 결과**로 정렬한다. 다 세어 봐야 순서를 아니, 어떤 인덱스도 미리 정렬해 둘 수 없다.

**인덱스가 해결할 수 있는 것과 없는 것이 갈리는 자리다.** 없앨 수 없는 것을 없애려고
인덱스를 더 만드는 것이 흔한 낭비다.

> **곁가지**: 서브쿼리 `(SELECT COUNT(*) FROM playthroughs)` 가 `fk_playthroughs_user` 를 훑으며
> `Using index` 다. 행 수를 세는 데 테이블이 아니라 **가장 작은 인덱스**를 고른 것 —
> 아무도 시키지 않았고, 옵티마이저가 알아서 한 일이다.

---

## 2. 선택 비율 (`sql/stats/choice_ratio.sql`) — **가장 큰 변화**

```sql
SELECT ch.episode_id, ch.option_index, COUNT(*)
FROM choice_history ch
JOIN chapter_contents c ON c.id = ch.chapter_content_id
WHERE c.chapter_id = 'qwer' AND c.version = 1
GROUP BY ch.episode_id, ch.option_index;
```

### 전

| id | table | type | possible_keys | key | key_len | ref | rows | Extra |
|---|---|---|---|---|---|---|---|---|
| 1 | `c` | const | PRIMARY, uk_chapter_version | uk_chapter_version | 206 | const,const | 1 | Using index; **Using temporary** |
| 1 | `ch` | **ALL** | fk_choice_episode | **NULL** | | | 200 | Using where |

### 후

| id | table | type | possible_keys | key | key_len | ref | rows | Extra |
|---|---|---|---|---|---|---|---|---|
| 1 | `c` | const | PRIMARY, uk_chapter_version | uk_chapter_version | 206 | const,const | 1 | Using index |
| 1 | `ch` | **ref** | **ix_choice_stats** | **ix_choice_stats** | 8 | const | 200 | **Using index** |

### 읽기

네 가지가 한꺼번에 바뀌었다.

1. **`type: ALL → ref`** — 전체 스캔이 인덱스 조회가 됐다.
2. **`key: NULL → ix_choice_stats`** — 전에는 인덱스가 **있는데도 안 썼다.**
   `fk_choice_episode` 가 `(chapter_content_id, episode_id)` 로 있었지만,
   200행 전부가 그 `chapter_content_id` 라 "인덱스 타고 다시 테이블 찾아가느니 그냥 훑자" 가 더 쌌다.
3. **`Using index`** — 이제 `option_index` 까지 인덱스에 있어 테이블을 안 연다. 이것이 판단을 뒤집었다.
4. **`Using temporary` 가 사라졌다** — `GROUP BY episode_id, option_index` 가 인덱스 순서와 같아서
   **읽으면서 바로 묶인다.** 임시 테이블을 만들 이유가 없어졌다.

**`rows` 는 200 그대로다.** 읽는 행 수는 하나도 안 줄었는데 방식이 완전히 달라졌다 —
`rows` 만 보고 인덱스를 평가하면 이 개선을 통째로 놓친다.

`key_len 8` 은 `chapter_content_id`(BIGINT) 하나뿐이다. **WHERE 로 좁히는 데 쓴 부분**이 8바이트라는 뜻이고,
나머지 두 컬럼은 좁히는 데가 아니라 **정렬과 커버링**에 쓰인다. `key_len` 은 인덱스 전체 폭이 아니다.

---

## 3. 사용자 요약 (`sql/stats/user_summary.sql`) — **아무것도 안 했고, 그것이 결론이다**

```sql
SELECT u.id, COUNT(DISTINCT p.id), COUNT(DISTINCT s.id)
FROM users u
LEFT JOIN playthroughs p ON p.user_id = u.id
LEFT JOIN save_slots   s ON s.playthrough_id = p.id
WHERE u.id = 1 GROUP BY u.id;
```

### 전 / 후 — **동일**

| table | type | key | key_len | ref | rows | Extra |
|---|---|---|---|---|---|---|
| `u` | const | PRIMARY | 8 | const | 1 | Using index |
| `p` | ref | fk_playthroughs_user | 8 | const | 4 | Using index |
| `s` | ref | uk_save_slot | 8 | game.p.id | 1 | Using index |

### 읽기

셋 다 `ref`/`const` 에 `Using index`. **손댈 데가 없다.**
V3 에 "user_summary 에는 아무것도 추가하지 않는다" 고 적었는데, 이제 그것이 **주장이 아니라 측정**이다.

그런데 쓰인 인덱스들의 정체를 보라:

| 인덱스 | 원래 만든 이유 | M |
|---|---|---|
| `PRIMARY` | 식별자 | M0 |
| `fk_playthroughs_user` | **FK 제약** | M2 |
| `uk_save_slot (playthrough_id, slot_no)` | **UNIQUE 제약** — upsert 의 키 | M2 |

전부 **무결성을 위해** 걸어 둔 것이고, 조회 성능을 노린 것이 하나도 없다.
`uk_save_slot` 은 M2 에서 "두 요청이 겹칠 때 UNIQUE 만이 확실한 방어선" 이라고 건 것인데,
M5 에서 `playthrough_id` 로 슬롯을 찾는 인덱스 노릇을 하고 있다.

**제약을 제대로 걸면 인덱스는 덤으로 따라온다.** 반대로 제약 없이 인덱스만 만들면
데이터가 깨지는 것은 못 막으면서 유지 비용만 문다.

---

## 4. 정리 — 이번에 배운 것

1. **`Extra` 가 `rows` 보다 정보량이 많다.** ② 는 `rows` 가 200 그대로인데 실행 방식이 통째로 바뀌었다.
2. **커버링 인덱스는 작은 테이블에서도 참이다.** "테이블 본체를 안 봤다" 는 행 수와 무관한 사실이다.
3. **인덱스가 있어도 안 쓸 수 있다.** ② 의 전(前) 이 그랬다. 옵티마이저는 "인덱스를 타는 비용"까지 계산한다.
4. **인덱스로 못 없애는 것이 있다.** 집계 결과로 정렬하는 `ORDER BY` 는 어떤 인덱스도 미리 준비해 둘 수 없다(①).
5. **필요 없으면 안 넣는 것도 결정이다.** ③ 에 인덱스를 하나도 추가하지 않았고, 그 판단의 근거가 이 표다.
6. **제약이 인덱스가 된다.** ③ 이 쓰는 인덱스 셋은 전부 PK·FK·UNIQUE 다.

## 5. 남겨 둔 것

- **`choice_history.fk_choice_episode` 는 `ix_choice_stats` 의 왼쪽 접두사라 중복이다.**
  지우지 않았다 — **FK 가 쓰고 있고**, 어느 인덱스가 FK 를 지탱하는지는 옵티마이저가 정한다.
  잘못 건드리면 제약 자체가 위험해진다. 얻는 것(공간 조금)보다 잃을 것이 크다.
  **모르고 남긴 것과 알고 남긴 것은 다르므로** 여기 적어 둔다.
- **`event_log.ix_event_key` 는 지웠다.** FK 가 아니었고 `ix_event_stats` 의 접두사라 안전했다.
  인덱스를 더할 때는 **중복이 생기는지** 함께 본다.
- 데이터가 커지면 이 표는 다시 찍어야 한다. **옵티마이저의 판단은 데이터 분포에 달렸다** —
  ② 의 전/후가 그것을 보여준다. 지금의 "정답" 이 10만 행에서도 정답이라는 보장은 없다.
