-- =====================================================================
--  사용자 요약 — GET /users/{userId}/summary
-- =====================================================================
--  정의:
--    playthroughs        이 사용자의 회차 수
--    endedPlaythroughs   그중 ended_at 이 있는 것
--    saveSlots           회차들이 가진 슬롯의 총합
--    choices             슬롯들에 쌓인 선택의 총합
--    playSeconds         슬롯 play_seconds 의 총합
--    lastPlayedAt        가장 최근 슬롯 갱신 시각 (없으면 NULL)
--
-- ---------------------------------------------------------------------
--  JOIN 팬아웃 — 이 쿼리가 가르치는 것
-- ---------------------------------------------------------------------
--  users ⋈ playthroughs ⋈ save_slots 로 이어 붙이면 행이 **곱해진다.**
--  회차 4개에 슬롯이 6개면 결과는 6행이고, 회차 id 는 그 안에서 여러 번 반복된다.
--  이 상태에서 COUNT(p.id) 를 쓰면 회차가 6 으로 세어진다 — 틀린 답이다.
--
--    COUNT(DISTINCT p.id)   회차 4  ← 맞다
--    COUNT(p.id)            회차 6  ← 팬아웃을 그대로 센 것
--
--  세는 것(COUNT)은 DISTINCT 로 고칠 수 있다. 그런데 **더하는 것(SUM)은 못 고친다** —
--  SUM(DISTINCT s.play_seconds) 는 "같은 값을 가진 두 슬롯" 을 하나로 합쳐 버린다.
--  값이 우연히 같다고 같은 행이 아니다.
--
--  그래서 SUM 계열은 **스칼라 서브쿼리**로 따로 뽑는다. 조인을 늘려 한 번에 끝내려다
--  조용히 틀린 숫자를 내는 것보다, 쿼리가 몇 줄 길어지는 편이 낫다.
--  (M5 에서 숫자가 틀리는 대부분의 원인은 SQL 문법이 아니라 이런 자리다.)
--
--  LEFT JOIN 인 이유: 회차가 하나도 없는 사용자도 0 으로 나와야 한다.
--  INNER 면 그 사용자는 결과에서 통째로 사라지고, 클라는 404 인지 0 인지 구분할 수 없다 —
--  M3 에서 "회차가 없다(404)" 와 "이벤트가 0개(빈 배열)" 를 나눈 것과 같은 이야기다.
--
--  Workbench 에서 돌릴 때는 :userId → 1 로 바꾼다.
-- =====================================================================
SELECT u.id                                                        AS userId,
       u.username                                                  AS username,
       COUNT(DISTINCT p.id)                                        AS playthroughs,
       COUNT(DISTINCT CASE WHEN p.ended_at IS NOT NULL THEN p.id END) AS endedPlaythroughs,
       COUNT(DISTINCT s.id)                                        AS saveSlots,
       -- 아래 둘은 팬아웃을 피해 따로 센다 (위 주석 참조)
       COALESCE((SELECT COUNT(*)
                 FROM choice_history ch
                 JOIN save_slots s2     ON s2.id = ch.save_slot_id
                 JOIN playthroughs p2   ON p2.id = s2.playthrough_id
                 WHERE p2.user_id = u.id), 0)                      AS choices,
       COALESCE((SELECT SUM(s3.play_seconds)
                 FROM save_slots s3
                 JOIN playthroughs p3   ON p3.id = s3.playthrough_id
                 WHERE p3.user_id = u.id), 0)                      AS playSeconds,
       MAX(s.updated_at)                                           AS lastPlayedAt
FROM users u
LEFT JOIN playthroughs p ON p.user_id = u.id
LEFT JOIN save_slots   s ON s.playthrough_id = p.id
WHERE u.id = :userId
GROUP BY u.id, u.username
