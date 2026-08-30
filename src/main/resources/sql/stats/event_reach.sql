-- =====================================================================
--  이벤트 도달률 — GET /stats/events
-- =====================================================================
--  정의 (이 두 줄이 이 파일에서 가장 중요하다):
--    분자 = 그 EventKey 가 기록된 **서로 다른 회차 수**
--    분모 = **전체 회차 수** (종료 여부와 무관)
--
--  분모를 "종료된 회차" 로 바꾸면 같은 데이터에서 다른 답이 나온다.
--  seed 기준: ENDING_A 가 전체 20 기준 40%, 종료 12 기준 66.7%.
--  둘 다 맞는 숫자이고, **무엇을 물었는지가 다르다.**
--    "플레이한 사람 중 몇이 이 엔딩을 봤나"        → 전체 회차 (이 파일)
--    "끝까지 간 사람 중 몇이 이 엔딩을 골랐나"      → 종료 회차
--  전자를 택한 이유: 진행 중인 회차도 언젠가 도달할 수 있으므로,
--  "지금까지 이 이벤트를 본 비율" 이라는 물음에는 전체가 분모다.
--
--  COUNT(DISTINCT playthrough_id) 인 이유:
--  uk_event_once 가 회차당 EventKey 를 한 번으로 막고 있으므로 지금은 COUNT(*) 와 같다.
--  그러나 M6 PLAN#4 로 UNIQUE 범위가 바뀌면(버전별 → 회차 내 1회) 달라질 수 있다.
--  **제약이 보장하는 것에 기대지 않고 쿼리가 스스로 말하게 둔다.**
--
--  Workbench 에서 그대로 돌아간다 (파라미터가 없다).
-- =====================================================================
SELECT e.event_key                                       AS eventKey,
       COUNT(DISTINCT e.playthrough_id)                  AS reachedPlaythroughs,
       (SELECT COUNT(*) FROM playthroughs)               AS totalPlaythroughs,
       ROUND(COUNT(DISTINCT e.playthrough_id) * 100.0
             / NULLIF((SELECT COUNT(*) FROM playthroughs), 0), 1) AS reachRate,
       MIN(e.occurred_at)                                AS firstOccurredAt,
       MAX(e.occurred_at)                                AS lastOccurredAt
FROM event_log e
GROUP BY e.event_key
ORDER BY reachedPlaythroughs DESC, e.event_key
