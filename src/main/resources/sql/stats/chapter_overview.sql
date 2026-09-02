-- =====================================================================
--  챕터 개요 — GET /stats/chapters/{chapterId}/overview?version=   (M9-1, D-025)
-- =====================================================================
--  물음: "이 챕터(이 버전)를 몇 회차가 시작했고, 몇 회차가 끝냈나."
--  관리자 화면의 첫 카드다. 통계의 첫 번째 숫자는 언제나 완주율이다.
--
--  정의:
--    playthroughs     슬롯 1 이 이 챕터 버전을 가리키는 회차 수 (갈래 단위, D-020)
--    completed        그중 슬롯 1 의 chapter_completed 가 참인 것 — "끝낸 회차" (D-025: ended_at 이 아니다)
--    completionRate   completed / playthroughs × 100, 소수 1자리. 회차가 0 이면 NULL 이 아니라 0.0
--    forks            그중 갈라진 회차 (forked_from_client_id IS NOT NULL)
--    bookmarks        이 챕터 버전을 가리키는 즐겨찾기 수 (삭제 제외). 회차 소속이 아니라 유저 소속이라 따로 센다
--
--  "슬롯 1" 로 한정하는 이유: 클라는 회차당 슬롯 하나를 쓴다(핸드오프 R5). 슬롯을 다 세면 seed 처럼 슬롯 둘인
--  회차가 두 번 세어진다 — M5 팬아웃의 그 자리다. 회차 수는 COUNT(DISTINCT) 로 한 번 더 막는다.
--
--  회차가 하나도 없는 버전도 한 줄이 나와야 한다(0/0/0.0). 그래서 chapter_contents 에서 출발해 LEFT JOIN 한다.
--  없는 버전은 0행 — 서비스가 404 로 가른다 (choice_ratio 와 같은 규칙).
--
--  Workbench 에서 돌릴 때는 :chapterId → 'qwer', :version → 1 로 바꾼다. seed 기대값: 20 / 0 / 0.0 / 0 / 0.
-- =====================================================================
SELECT c.chapter_id                                                   AS chapterId,
       c.version                                                      AS version,
       COUNT(DISTINCT p.id)                                           AS playthroughs,
       COUNT(DISTINCT CASE WHEN s.chapter_completed THEN p.id END)   AS completed,
       COALESCE(ROUND(COUNT(DISTINCT CASE WHEN s.chapter_completed THEN p.id END) * 100.0
                      / NULLIF(COUNT(DISTINCT p.id), 0), 1), 0.0)     AS completionRate,
       COUNT(DISTINCT CASE WHEN p.forked_from_client_id IS NOT NULL THEN p.id END) AS forks,
       (SELECT COUNT(*) FROM bookmarks b
         WHERE b.chapter_content_id = c.id AND b.deleted_at IS NULL)  AS bookmarks
FROM chapter_contents c
LEFT JOIN save_slots   s ON s.chapter_content_id = c.id AND s.slot_no = 1
LEFT JOIN playthroughs p ON p.id = s.playthrough_id
WHERE c.chapter_id = :chapterId
  AND c.version    = :version
GROUP BY c.id, c.chapter_id, c.version
