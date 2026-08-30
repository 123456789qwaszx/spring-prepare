-- =====================================================================
--  선택 비율 — GET /stats/chapters/{chapterId}/choices?version=N
-- =====================================================================
--  정의:
--    분자 = 그 에피소드에서 그 옵션이 선택된 횟수
--    분모 = **그 에피소드에서의 총 선택 횟수** (챕터 전체가 아니다)
--  그래서 에피소드마다 비율의 합이 100% 가 된다.
--
--  범위: 특정 챕터의 **특정 버전**. 버전이 다르면 옵션 구성도 다를 수 있으므로
--  합치면 의미가 없다 — 세이브가 chapter_id 가 아니라 버전을 가리키는 것과 같은 이유다.
--
-- ---------------------------------------------------------------------
--  이 프로젝트에서 가장 어려운 SQL 이고, 어려운 이유가 셋이다
-- ---------------------------------------------------------------------
--  1. **라벨이 색인에 없다.** chapter_episodes 는 option_count(개수)만 들고 있고
--     ChoiceLabel 은 원본 JSON 안에 있다. 색인의 목적이 판정이 아니라 JOIN 이기 때문이다
--     (schema.sql 주석). 라벨은 표시용이라 색인에 복제하지 않았고, 그 대가를 여기서 치른다.
--
--  2. **JSON path 에 필터가 없다.** `$.Nodes[?(@.EpisodeId=="EP01")]` 같은 것을 MySQL 은 못 쓴다.
--     그래서 JSON_TABLE 로 Nodes 를 **행으로 펼친 뒤** episode_id 로 JOIN 한다.
--     NESTED PATH 로 NextOptions 까지 한 번에 펼치면 (에피소드 × 옵션) 한 행이 된다.
--
--  3. **FOR ORDINALITY 는 1부터 센다.** 우리 option_index 는 배열 인덱스라 0부터다.
--     그래서 `optionOrdinal - 1`. 이 한 칸 차이가 라벨을 통째로 밀리게 만든다 —
--     조용히 틀리는 종류의 버그라 seed 로 미리 답을 알고 대조하는 것이 유일한 방어다.
--
--  JSON_TABLE 을 LEFT JOIN 의 오른쪽에 직접 두지 않고 파생 테이블로 감싼 이유:
--  JSON_TABLE 은 앞선 테이블의 컬럼을 참조하는 lateral 이라 바깥 JOIN 과 섞으면
--  버전에 따라 제약이 생긴다. 한 겹 감싸면 그냥 평범한 테이블이 되고, 읽기도 쉬워진다.
--
--  LEFT JOIN 인 이유: 라벨이 없어도 **횟수는 나와야 한다.** 콘텐츠에서 옵션이 사라졌는데
--  이력에는 남아 있는 경우가 실제로 가능하다 (버전이 다른 이력은 아니지만, 데이터가 깨졌을 때).
--  INNER 로 두면 그 행이 조용히 사라져 합계가 안 맞는다 — M3 C5 와 반대 방향의 판단이다.
--
--  Workbench 에서 돌릴 때는 :chapterId → 'qwer', :version → 1 로 바꾼다.
-- =====================================================================
SELECT ch.episode_id                       AS episodeId,
       ep.title                            AS episodeTitle,
       ch.option_index                     AS optionIndex,
       labels.choice_label                 AS choiceLabel,
       COUNT(*)                            AS picks,
       ROUND(COUNT(*) * 100.0
             / SUM(COUNT(*)) OVER (PARTITION BY ch.episode_id), 1) AS pickRate
FROM choice_history ch
JOIN chapter_contents  c  ON c.id = ch.chapter_content_id
JOIN chapter_episodes  ep ON ep.chapter_content_id = ch.chapter_content_id
                         AND ep.episode_id         = ch.episode_id
LEFT JOIN (
        -- Nodes[*] × NextOptions[*] 를 (에피소드, 옵션번호, 라벨) 행으로 펼친다
        SELECT c2.id                AS chapter_content_id,
               jt.episode_id        AS episode_id,
               jt.option_ordinal - 1 AS option_index,     -- ← 1-기반을 0-기반으로
               jt.choice_label      AS choice_label
        FROM chapter_contents c2,
             JSON_TABLE(c2.body, '$.Nodes[*]' COLUMNS (
                 episode_id VARCHAR(50) PATH '$.EpisodeId',
                 NESTED PATH '$.NextOptions[*]' COLUMNS (
                     option_ordinal FOR ORDINALITY,
                     choice_label   VARCHAR(100) PATH '$.ChoiceLabel'
                 )
             )) AS jt
) labels ON labels.chapter_content_id = ch.chapter_content_id
        AND labels.episode_id         = ch.episode_id
        AND labels.option_index       = ch.option_index
WHERE c.chapter_id = :chapterId
  AND c.version    = :version
GROUP BY ch.episode_id, ep.title, ch.option_index, labels.choice_label
ORDER BY ch.episode_id, ch.option_index
