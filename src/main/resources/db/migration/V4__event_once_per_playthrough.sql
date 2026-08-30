-- =====================================================================
--  V4__event_once_per_playthrough.sql — event_log UNIQUE 축소 (D-011)
-- =====================================================================
--  v1 의 uk_event_once 는 (playthrough_id, event_key, chapter_content_id, episode_id) 로,
--  범위에 **콘텐츠 버전이 들어 있었다.** 챕터를 개편해 v2 를 올리면 같은 회차에서
--  ENDING_A 가 또 기록된다 — M5 의 도달률 쿼리에 COUNT(DISTINCT playthrough_id) 가
--  있는 이유가 이것이었다.
--
--  event_log 는 [1] 영구 계층이고, 영구 계층이 답하는 질문은 "이 회차에서 무슨 일이
--  있었나" 다. "어느 버전에서 봤나" 는 콘텐츠 창고의 관심사다. 그래서 회차 내
--  EventKey 당 1회로 좁힌다. 컬럼(chapter_content_id, episode_id)은 남긴다 —
--  "어느 버전의 어느 에피소드에서 **처음** 봤나" 는 여전히 유용하고 M5 의 JOIN 이 쓴다.
--
--  ⚠ 반드시 **한 문장**이어야 한다 (D-011 갱신).
--  uk_event_once 는 fk_event_playthrough(playthrough_id FK)를 지탱하는 **유일한
--  선두 인덱스**다 — FK 용 자동 인덱스는 지탱할 인덱스가 이미 있으면 만들어지지 않는다.
--  DROP 을 별도 문장으로 실행하면 1553(Cannot drop index: needed in a foreign key
--  constraint)이 난다. 한 문장 안에서 교체하면 새 UNIQUE 도 playthrough_id 선두라
--  FK 가 끊길 틈이 없다. (V3 주석의 "FK 가 쓰는 인덱스였다면 얘기가 다르다" 가
--  여기서 현실이 됐다.)
--
--  이름은 uk_event_once 를 그대로 쓴다 — "회차에서 한 번" 이라는 이름이
--  오히려 새 정의에 더 맞는다.
--
--  재생성 경로(M6-1b)에서는 빈 DB 에 적용되므로 기존 데이터 걱정이 없다.
--  만약 데이터가 있는 DB 에 적용해야 한다면 먼저 중복을 확인한다:
--    SELECT playthrough_id, event_key, COUNT(*)
--    FROM event_log GROUP BY 1, 2 HAVING COUNT(*) > 1;   -- 0행이라야 한다
--  (db/seed.sql 은 중복이 없음을 2026-08-30 확인했다.)
-- =====================================================================

ALTER TABLE event_log
    DROP KEY uk_event_once,
    ADD UNIQUE KEY uk_event_once (playthrough_id, event_key);
