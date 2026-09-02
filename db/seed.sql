-- =====================================================================
--  db/seed.sql — M5 집계 학습용 더미 데이터
-- =====================================================================
--
--  목적: 집계 쿼리의 답을 **손으로 미리 알 수 있게** 만든다.
--  그래서 난수를 쓰지 않는다. 모든 분포는 아래 설계표로 정해져 있고,
--  SQL 의 CASE 식이 그 표를 그대로 옮긴 것이다 — 데이터가 곧 명세다.
--
--  재실행 가능하다. 맨 앞에서 전부 지우고 AUTO_INCREMENT 를 되돌리므로
--  몇 번을 돌려도 같은 id, 같은 숫자가 나온다.
--
--  적용 대상: **접속한 커넥션의 기본 스키마.** 테이블 이름에 스키마를 붙이지 않았다.
--  같은 파일이 game 과 game_test 양쪽에 그대로 쓰이려면 그래야 하기 때문이다.
--
--    Workbench →  좌측 SCHEMAS 에서 `game` 을 **더블클릭**해 굵게 만든 뒤(= 기본 스키마 지정),
--                 File ▸ Open SQL Script 로 이 파일을 열고 ⚡⚡ (Execute All, Ctrl+Shift+Enter).
--                 ※ `SOURCE` 는 mysql 명령줄 클라이언트의 명령이라 Workbench 에서는 동작하지 않는다.
--    테스트     →  game_test 커넥션에 ScriptUtils 로 자동 적용 (M5-4)
--
--  기본 스키마를 확인하려면 `SELECT DATABASE();` — `game` 이 나와야 한다.
--
--  ⚠ 이 스크립트는 **테이블을 전부 비운다.** M2~M4 의 수동 확인 데이터는 사라진다.
--    그 결과는 각 M 의 check 문서에 이미 기록돼 있으므로 잃을 것이 없다.
--
-- ---------------------------------------------------------------------
--  설계표 — 이 숫자들이 M5 완료 기준의 "수작업 계산값"이다
-- ---------------------------------------------------------------------
--
--  사용자 5명, 회차 20개 (사용자당 4개), 선택 200건, 이벤트 27건
--
--  ▸ 회차 (playthroughs.id = 1~20)
--      id  1~12  종료됨 (ended_at 있음)          12개
--      id 13~20  진행 중 (ended_at NULL)          8개
--
--  ▸ 이벤트 도달 (event_log)
--      MILESTONE_MIDPOINT  회차  1~15   15개
--      ENDING_A            회차  1~8     8개
--      ENDING_B            회차  9~12    4개
--                                       ─────
--                                        27건
--
--      **엔딩을 본 회차(8+4=12) = 종료된 회차(12)** 로 맞춰 두었다.
--      그래서 "도달률" 의 분모를 무엇으로 잡느냐가 답을 바꾼다:
--          전체 회차 20 기준 → ENDING_A 40%, ENDING_B 20%  (합 60%)
--          종료 회차 12 기준 → ENDING_A 66.7%, ENDING_B 33.3% (합 100%)
--      숫자가 틀리는 대부분의 원인은 SQL 이 아니라 **정의**다 (M5.md §3-3).
--      이 데이터는 그 사실이 눈에 보이도록 만들어졌다.
--
--  ▸ 선택 (choice_history) — 회차마다 슬롯 1 에 seq 1~10, 총 200건
--      seq  1~5   EP01      (옵션 3개)  100건
--      seq  6~8   EP03_02   (옵션 2개)   60건
--      seq  9~10  EP02_01   (옵션 1개)   40건
--
--      EP01 의 옵션 분포            EP03_02 의 옵션 분포
--        회차  1~10 → option 0  50건    회차  1~12 → option 0  36건  (60%)
--        회차 11~16 → option 1  30건    회차 13~20 → option 1  24건  (40%)
--        회차 17~20 → option 2  20건
--                    (50% / 30% / 20%)
--
--      한 회차에서 EP01 을 다섯 번 고르는 것은 현실적이지 않다.
--      VN 의 되돌아가기(롤백)로 같은 노드를 여러 번 지난다고 보면 되고,
--      무엇보다 **집계를 배우는 것이 목적**이라 분포의 명확성을 택했다.
--
--  ▸ 슬롯 (save_slots) — 30개
--      회차  1~10 → 슬롯 1개
--      회차 11~20 → 슬롯 2개 (1, 2)
--      선택 이력은 **슬롯 1 에만** 쌓는다.
--
--  ▸ 시각은 전부 UTC (D-009). 결정적 값이라 "어제/오늘" 같은 상대 표현이 없다.
-- =====================================================================

-- Workbench 는 접속할 때 safe update mode 를 켜므로 WHERE 없는 DELETE 가 막힌다 (M2 F22).
-- 앱 커넥션에는 이 설정이 없다(서버 기본값이 OFF) — 그래서 0 으로 맞추는 것은 양쪽 모두에 안전하다.
--
-- ⚠ **끝에서 1 로 되돌리지 않는다.** 그러면 커넥션 풀이 오염된다:
--    이 스크립트를 돌린 커넥션이 safe mode 가 켜진 채 풀로 돌아가고,
--    다음에 그 커넥션을 빌린 테스트의 DbCleaner 가 오류 1175 로 죽는다.
--    세션 변수는 커넥션에 붙어 있지 트랜잭션과 함께 사라지지 않는다.
--
--    "원상복구" 를 하려면 **원래 값이 무엇인지** 알아야 하는데, 이 파일은 두 환경에서 돌고
--    두 환경의 원래 값이 다르다 (Workbench 1, 앱 0). 그래서 되돌리지 않는 것이 옳다.
SET SQL_SAFE_UPDATES = 0;

-- ── 1. 정리 (자식 → 부모). DbCleaner 와 같은 순서다 ────────────────
-- sessions 는 M6 에서 생겼다 (V5). users 를 참조하므로 users 보다 먼저 지운다.
-- (token 이 PK 라 AUTO_INCREMENT 재설정은 필요 없다.)
DELETE FROM sessions;
DELETE FROM bookmarks;                              -- M8-A (V6)
UPDATE playthroughs SET forked_from_id = NULL;      -- M8-A: 자기 참조 FK 를 먼저 끊어야 아래 DELETE 가 통과한다
DELETE FROM choice_history;
DELETE FROM event_log;
DELETE FROM save_slots;
DELETE FROM playthroughs;
DELETE FROM chapter_episodes;
DELETE FROM chapter_contents;
DELETE FROM game_definitions;
DELETE FROM devices;
DELETE FROM users;

-- AUTO_INCREMENT 를 되돌린다. DELETE 는 행만 지우고 다음 번호는 그대로 두므로
-- (M3-check §1.4), 이걸 안 하면 돌릴 때마다 id 가 달라져 "결정적" 이 깨진다.
-- 테이블이 비어 있을 때만 안전하다 — 위에서 방금 비웠다.
ALTER TABLE users            AUTO_INCREMENT = 1;
ALTER TABLE devices          AUTO_INCREMENT = 1;
ALTER TABLE game_definitions AUTO_INCREMENT = 1;
ALTER TABLE chapter_contents AUTO_INCREMENT = 1;
ALTER TABLE playthroughs     AUTO_INCREMENT = 1;
ALTER TABLE save_slots       AUTO_INCREMENT = 1;
ALTER TABLE choice_history   AUTO_INCREMENT = 1;
ALTER TABLE event_log        AUTO_INCREMENT = 1;
ALTER TABLE bookmarks        AUTO_INCREMENT = 1;   -- M8-A (V6)

-- ── 2. 사용자 5명 (id 1~5) ────────────────────────────────────────
-- 비밀번호는 전부 문자열 'seed-only' 의 BCrypt 해시다 (M6-3b).
-- 평문을 넣으면 seed 를 심을 때마다 M6 완료 기준("SELECT password 에 평문 없음")이
-- 깨지고, 테스트가 seed 사용자로 로그인할 수도 없다. 해시는 미리 계산해 박아 둔다 —
-- BCrypt 검증은 해시 문자열 안의 cost(여기서는 10)를 따르므로 앱의 strength 설정과 무관하다.
-- 다섯 명이 같은 해시인 것은 의도다: 같은 비밀번호 + 같은 salt 재사용이 아니라,
-- 한 번 만든 해시 한 개를 다섯 행에 넣은 것뿐이다 (seed 는 결정적이어야 한다).
INSERT INTO users (username, password, created_at) VALUES
  ('amiya',  '$2a$10$WCnXu2Sw.U/.ifaxfEYzA.alY7bEGCFyHmZTb4vFpyEkwP0NFv5g.', '2026-08-01 00:00:00'),
  ('bailu',  '$2a$10$WCnXu2Sw.U/.ifaxfEYzA.alY7bEGCFyHmZTb4vFpyEkwP0NFv5g.', '2026-08-01 00:00:00'),
  ('chen',   '$2a$10$WCnXu2Sw.U/.ifaxfEYzA.alY7bEGCFyHmZTb4vFpyEkwP0NFv5g.', '2026-08-01 00:00:00'),
  ('dusk',   '$2a$10$WCnXu2Sw.U/.ifaxfEYzA.alY7bEGCFyHmZTb4vFpyEkwP0NFv5g.', '2026-08-01 00:00:00'),
  ('eyja',   '$2a$10$WCnXu2Sw.U/.ifaxfEYzA.alY7bEGCFyHmZTb4vFpyEkwP0NFv5g.', '2026-08-01 00:00:00');

-- ── 3. 기기 — 사용자당 1대. 회차 요약이 "어느 기기" 를 보여줄 재료 ──
INSERT INTO devices (user_id, device_key, display_name, last_seen_at)
SELECT u.id, CONCAT('device-', u.username), CONCAT(u.username, ' 의 PC'), '2026-08-01 00:00:00'
FROM users u;

-- ── 4. 콘텐츠 — qwer v1 ───────────────────────────────────────────
-- body 에 Nodes 를 넣는 이유: M5 의 "선택 비율" 쿼리가 여기서 ChoiceLabel 을 뽑는다.
-- JSON_TABLE 로 Nodes 를 행으로 펼쳐 episode_id 로 JOIN 하는 것이 M5 에서 가장 어려운 SQL 이다.
-- (색인 테이블 chapter_episodes 에는 라벨이 없다 — 라벨은 표시용이지 판정용이 아니므로.)
INSERT INTO chapter_contents (chapter_id, version, display_name, start_episode_id, body, checksum, imported_at)
VALUES ('qwer', 1, '큐더블유이알', 'EP01', '{
  "ChapterId": "qwer",
  "DisplayName": "큐더블유이알",
  "StartEpisodeId": "EP01",
  "Nodes": [
    {"EpisodeId": "EP01", "Title": "출발", "EventKey": "", "NextOptions": [
      {"TargetEpisodeId": "EP02_01", "ChoiceLabel": "성실하게 간다"},
      {"TargetEpisodeId": "EP02_02", "ChoiceLabel": "요령껏 간다"},
      {"TargetEpisodeId": "EP02_03", "ChoiceLabel": "그냥 간다"}
    ]},
    {"EpisodeId": "EP02_01", "Title": "성실한 길", "EventKey": "", "NextOptions": [
      {"TargetEpisodeId": "EP03_01", "ChoiceLabel": "계속 걷는다"}
    ]},
    {"EpisodeId": "EP02_02", "Title": "요령의 길", "EventKey": "", "NextOptions": [
      {"TargetEpisodeId": "EP03_02", "ChoiceLabel": "지름길로"}
    ]},
    {"EpisodeId": "EP02_03", "Title": "그냥 길", "EventKey": "", "NextOptions": [
      {"TargetEpisodeId": "EP03_02", "ChoiceLabel": "터벅터벅"}
    ]},
    {"EpisodeId": "EP03_01", "Title": "중간 지점", "EventKey": "MILESTONE_MIDPOINT", "NextOptions": []},
    {"EpisodeId": "EP03_02", "Title": "갈림길", "EventKey": "", "NextOptions": [
      {"TargetEpisodeId": "EP04_01", "ChoiceLabel": "왼쪽으로"},
      {"TargetEpisodeId": "EP04_02", "ChoiceLabel": "오른쪽으로"}
    ]},
    {"EpisodeId": "EP04_01", "Title": "엔딩 A", "EventKey": "ENDING_A", "NextOptions": []},
    {"EpisodeId": "EP04_02", "Title": "엔딩 B", "EventKey": "ENDING_B", "NextOptions": []}
  ]
}', REPEAT('5', 64), '2026-08-01 00:00:00');

-- 색인. 수입 API 가 만드는 것과 같은 내용을 손으로 넣는다
-- (seed 는 M1 의 수입 경로를 거치지 않는다 — 집계를 시험하는 것이 목적이므로).
INSERT INTO chapter_episodes (chapter_content_id, episode_id, title, event_key, option_count)
SELECT c.id, e.episode_id, e.title, e.event_key, e.option_count
FROM chapter_contents c
CROSS JOIN (
          SELECT 'EP01'    AS episode_id, '출발'   AS title, ''                 AS event_key, 3 AS option_count
UNION ALL SELECT 'EP02_01',               '성실한 길',        '',                      1
UNION ALL SELECT 'EP02_02',               '요령의 길',        '',                      1
UNION ALL SELECT 'EP02_03',               '그냥 길',          '',                      1
UNION ALL SELECT 'EP03_01',               '중간 지점',        'MILESTONE_MIDPOINT',    0
UNION ALL SELECT 'EP03_02',               '갈림길',           '',                      2
UNION ALL SELECT 'EP04_01',               '엔딩 A',           'ENDING_A',              0
UNION ALL SELECT 'EP04_02',               '엔딩 B',           'ENDING_B',              0
) e
WHERE c.chapter_id = 'qwer' AND c.version = 1;

-- ── 5. 회차 20개 (id 1~20) ────────────────────────────────────────
-- user_id 는 4개씩 묶어 1~5. 시각은 id 로 계산해 결정적으로 둔다.
-- 1~12 는 종료됨, 13~20 은 진행 중.
INSERT INTO playthroughs (user_id, started_at, ended_at) VALUES
  (1, '2026-08-02 01:00:00', '2026-08-02 03:00:00'),   -- 1
  (1, '2026-08-02 02:00:00', '2026-08-02 04:00:00'),   -- 2
  (1, '2026-08-02 03:00:00', '2026-08-02 05:00:00'),   -- 3
  (1, '2026-08-02 04:00:00', '2026-08-02 06:00:00'),   -- 4
  (2, '2026-08-02 05:00:00', '2026-08-02 07:00:00'),   -- 5
  (2, '2026-08-02 06:00:00', '2026-08-02 08:00:00'),   -- 6
  (2, '2026-08-02 07:00:00', '2026-08-02 09:00:00'),   -- 7
  (2, '2026-08-02 08:00:00', '2026-08-02 10:00:00'),   -- 8   ← 여기까지 ENDING_A
  (3, '2026-08-02 09:00:00', '2026-08-02 11:00:00'),   -- 9
  (3, '2026-08-02 10:00:00', '2026-08-02 12:00:00'),   -- 10
  (3, '2026-08-02 11:00:00', '2026-08-02 13:00:00'),   -- 11
  (3, '2026-08-02 12:00:00', '2026-08-02 14:00:00'),   -- 12  ← 여기까지 ENDING_B / 종료됨
  (4, '2026-08-02 13:00:00', NULL),                    -- 13
  (4, '2026-08-02 14:00:00', NULL),                    -- 14
  (4, '2026-08-02 15:00:00', NULL),                    -- 15  ← 여기까지 MILESTONE
  (4, '2026-08-02 16:00:00', NULL),                    -- 16
  (5, '2026-08-02 17:00:00', NULL),                    -- 17
  (5, '2026-08-02 18:00:00', NULL),                    -- 18
  (5, '2026-08-02 19:00:00', NULL),                    -- 19
  (5, '2026-08-02 20:00:00', NULL);                    -- 20

-- ── 6. 슬롯 30개 ──────────────────────────────────────────────────
-- 슬롯 1: 모든 회차 (20개). 선택 이력이 여기 붙는다.
INSERT INTO save_slots (playthrough_id, slot_no, chapter_content_id, current_episode_id,
                        snapshot, revision, play_seconds, device_id, updated_at)
SELECT p.id, 1, c.id,
       CASE WHEN p.id <=  8 THEN 'EP04_01'
            WHEN p.id <= 12 THEN 'EP04_02'
            ELSE 'EP03_02' END,
       JSON_OBJECT('nodeName', CONCAT('qwer_', p.id), 'variables', JSON_OBJECT('int', p.id)),
       3,
       p.id * 100,                                   -- 100 ~ 2000 초
       (SELECT d.id FROM devices d WHERE d.user_id = p.user_id),
       DATE_ADD('2026-08-02 00:00:00', INTERVAL p.id HOUR)
FROM playthroughs p
JOIN chapter_contents c ON c.chapter_id = 'qwer' AND c.version = 1;

-- 슬롯 2: 회차 11~20 만 (10개). "회차마다 슬롯 수가 다르다" 를 만드는 자리 —
-- 회차 요약이 COUNT 를 세는 대상이다.
INSERT INTO save_slots (playthrough_id, slot_no, chapter_content_id, current_episode_id,
                        snapshot, revision, play_seconds, device_id, updated_at)
SELECT p.id, 2, c.id, 'EP02_01',
       JSON_OBJECT('nodeName', CONCAT('qwer_', p.id, '_b')),
       1,
       p.id * 10,
       (SELECT d.id FROM devices d WHERE d.user_id = p.user_id),
       DATE_ADD('2026-08-02 00:00:00', INTERVAL p.id HOUR)
FROM playthroughs p
JOIN chapter_contents c ON c.chapter_id = 'qwer' AND c.version = 1
WHERE p.id >= 11;

-- ── 7. 선택 200건 ─────────────────────────────────────────────────
-- 회차마다 슬롯 1 에 seq 1~10. 아래 두 CASE 식이 설계표를 그대로 옮긴 것이다.
--
-- 숫자 1~10 을 만드는 방법으로 재귀 CTE 대신 UNION ALL 을 쓴다.
-- 짧지는 않지만 **무엇이 들어가는지가 한눈에 보이고**, MySQL 버전에 따른
-- `INSERT … WITH … SELECT` 지원 차이를 신경 쓸 필요가 없다.
INSERT INTO choice_history (save_slot_id, seq, chapter_content_id, episode_id,
                            option_index, chosen_at, received_at)
SELECT s.id,
       n.i,
       s.chapter_content_id,
       CASE WHEN n.i <= 5 THEN 'EP01'
            WHEN n.i <= 8 THEN 'EP03_02'
            ELSE             'EP02_01' END,
       CASE
         -- EP01 (seq 1~5): 회차 구간이 곧 옵션 번호다 → 50 / 30 / 20
         WHEN n.i <= 5 THEN CASE WHEN s.playthrough_id <= 10 THEN 0
                                 WHEN s.playthrough_id <= 16 THEN 1
                                 ELSE 2 END
         -- EP03_02 (seq 6~8): 12회차 / 8회차 → 36 / 24
         WHEN n.i <= 8 THEN CASE WHEN s.playthrough_id <= 12 THEN 0 ELSE 1 END
         -- EP02_01 (seq 9~10): 옵션이 하나뿐이다 → 전부 0
         ELSE 0
       END,
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (s.playthrough_id * 60 + n.i) MINUTE),
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (s.playthrough_id * 60 + n.i) MINUTE)
FROM save_slots s
CROSS JOIN (          SELECT 1 AS i
            UNION ALL SELECT 2  UNION ALL SELECT 3  UNION ALL SELECT 4  UNION ALL SELECT 5
            UNION ALL SELECT 6  UNION ALL SELECT 7  UNION ALL SELECT 8  UNION ALL SELECT 9
            UNION ALL SELECT 10) n
WHERE s.slot_no = 1;

-- ── 8. 이벤트 27건 ────────────────────────────────────────────────
-- uk_event_once (playthrough_id, event_key, chapter_content_id, episode_id) 를 지킨다:
-- 회차마다 각 EventKey 는 최대 한 번.
INSERT INTO event_log (playthrough_id, event_key, chapter_content_id, episode_id,
                       occurred_at, received_at)
SELECT p.id, 'MILESTONE_MIDPOINT', c.id, 'EP03_01',
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (p.id * 60 + 30) MINUTE),
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (p.id * 60 + 30) MINUTE)
FROM playthroughs p
JOIN chapter_contents c ON c.chapter_id = 'qwer' AND c.version = 1
WHERE p.id <= 15;

INSERT INTO event_log (playthrough_id, event_key, chapter_content_id, episode_id,
                       occurred_at, received_at)
SELECT p.id, 'ENDING_A', c.id, 'EP04_01',
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (p.id * 60 + 50) MINUTE),
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (p.id * 60 + 50) MINUTE)
FROM playthroughs p
JOIN chapter_contents c ON c.chapter_id = 'qwer' AND c.version = 1
WHERE p.id <= 8;

INSERT INTO event_log (playthrough_id, event_key, chapter_content_id, episode_id,
                       occurred_at, received_at)
SELECT p.id, 'ENDING_B', c.id, 'EP04_02',
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (p.id * 60 + 50) MINUTE),
       DATE_ADD('2026-08-02 00:00:00', INTERVAL (p.id * 60 + 50) MINUTE)
FROM playthroughs p
JOIN chapter_contents c ON c.chapter_id = 'qwer' AND c.version = 1
WHERE p.id BETWEEN 9 AND 12;

-- =====================================================================
--  검산 — 이 여섯 줄이 설계표와 맞으면 seed 가 제대로 들어간 것이다.
--  집계 API 를 만들기 **전에** 여기서 숫자를 먼저 확정한다.
-- =====================================================================
-- SELECT COUNT(*) AS users_5           FROM users;                               -- 5
-- SELECT COUNT(*) AS playthroughs_20   FROM playthroughs;                        -- 20
-- SELECT COUNT(*) AS ended_12          FROM playthroughs WHERE ended_at IS NOT NULL;  -- 12
-- SELECT COUNT(*) AS slots_30          FROM save_slots;                          -- 30
-- SELECT COUNT(*) AS choices_200       FROM choice_history;                      -- 200
-- SELECT COUNT(*) AS events_27         FROM event_log;                           -- 27
--
-- SELECT episode_id, option_index, COUNT(*) AS n
-- FROM choice_history GROUP BY episode_id, option_index ORDER BY episode_id, option_index;
--   EP01     0 → 50 | EP01     1 → 30 | EP01 2 → 20
--   EP02_01  0 → 40
--   EP03_02  0 → 36 | EP03_02  1 → 24
--
-- SELECT event_key, COUNT(*) AS n FROM event_log GROUP BY event_key;
--   ENDING_A 8 | ENDING_B 4 | MILESTONE_MIDPOINT 15
