-- =====================================================================
--  V3__stats_indexes.sql — M5 집계 쿼리를 위한 인덱스
-- =====================================================================
--  **V2 는 M1 이 썼다** (D-007, game_definitions.checksum). 그래서 이번이 V3 다.
--  번호는 이어 붙이는 것이지 M 번호를 따르지 않는다.
--
--  적용: game 과 game_test **양쪽에** (M6 Flyway 이전이라 아직 손으로 한다 — 그 불편이 PLAN#5 의 근거다)
--
-- ---------------------------------------------------------------------
--  원칙: **EXPLAIN 이 요구한 것만 넣는다**
-- ---------------------------------------------------------------------
--  인덱스는 공짜가 아니다. 읽기를 빠르게 하는 대신 **INSERT·UPDATE 마다 유지 비용**을 물고
--  공간을 쓴다. 이 프로젝트는 쓰기가 잦은 쪽(세이브 업로드)이라 더 그렇다.
--  그래서 "있으면 좋겠지" 로 넣지 않고, EXPLAIN 전/후를 `docs/M5-explain.md` 에 남긴다.
-- =====================================================================

-- ── 1. 이벤트 도달률 (stats/event_reach.sql) ──────────────────────
--
--  쿼리가 event_log 에서 필요로 하는 것:
--      GROUP BY event_key                    ← 정렬·묶기
--      COUNT(DISTINCT playthrough_id)        ← 중복 제거
--      MIN/MAX(occurred_at)                  ← 범위
--
--  세 컬럼을 이 순서로 담으면 **인덱스만 읽고 끝난다**(covering index).
--  테이블 본체를 안 봐도 되고, event_key 순으로 이미 정렬돼 있어 임시 테이블도 필요 없다.
--  순서가 중요하다: GROUP BY 컬럼이 맨 앞이라야 정렬을 건너뛸 수 있다.
ALTER TABLE event_log ADD INDEX ix_event_stats (event_key, playthrough_id, occurred_at);

--  기존 ix_event_key(event_key) 는 위 인덱스의 **왼쪽 접두사**다.
--  (event_key, …) 를 쓸 수 있는 쿼리는 전부 새 인덱스로도 된다 — 남겨 두면
--  INSERT 마다 같은 일을 두 번 하고 공간만 더 쓴다. **인덱스를 더하면 중복이 생기는지 본다.**
--
--  event_key 는 FK 가 아니므로 그냥 지울 수 있다. (FK 가 쓰는 인덱스였다면 얘기가 다르다 — 아래 참조)
ALTER TABLE event_log DROP INDEX ix_event_key;

-- ── 2. 선택 비율 (stats/choice_ratio.sql) ─────────────────────────
--
--  쿼리가 choice_history 에서 필요로 하는 것:
--      WHERE chapter_content_id = ?          ← 범위 좁히기
--      GROUP BY episode_id, option_index     ← 정렬·묶기
--      COUNT(*)                              ← 세기
--
--  복합 FK (chapter_content_id, episode_id) 가 이미 인덱스를 만들어 두었지만
--  **option_index 가 없어서** 묶을 때 임시 테이블·정렬이 필요하다. 한 칸을 더 붙인다.
ALTER TABLE choice_history ADD INDEX ix_choice_stats (chapter_content_id, episode_id, option_index);

--  ⚠ 여기서는 중복 인덱스를 **지우지 않는다.**
--  fk_choice_episode 의 인덱스가 (chapter_content_id, episode_id) 로 위 인덱스의 접두사이긴 하다.
--  그런데 그 인덱스는 **FK 가 쓰고 있다.** MySQL 은 FK 를 지탱할 인덱스가 남아 있으면 드롭을 허용하지만,
--  판단이 미묘하고(어느 인덱스가 FK 를 지탱하는지 옵티마이저가 정한다) 잘못 건드리면
--  **제약 자체가 사라질 수 있다.** 얻는 것(공간 조금)보다 잃을 것이 크다.
--
--  중복을 남긴다는 사실 자체는 기록해 둔다 — 모르고 남긴 것과 알고 남긴 것은 다르다.

-- ── 3. user_summary.sql 에는 아무것도 추가하지 않는다 ──────────────
--
--  이 쿼리가 타는 길은 이미 전부 인덱스가 있다:
--      playthroughs.user_id        → fk_playthroughs_user 의 인덱스
--      save_slots.playthrough_id   → uk_save_slot 의 왼쪽 접두사
--      choice_history.save_slot_id → uk_choice_seq 의 왼쪽 접두사
--
--  **UNIQUE 제약이 인덱스 노릇을 하고 있다.** M0~M4 에서 무결성을 위해 걸어 둔 것들이
--  M5 에서 조회 성능으로 되돌아온 셈이다. 필요 없는데 넣지 않는 것도 결정이고,
--  그 판단의 근거가 EXPLAIN 이다 (docs/M5-explain.md).
