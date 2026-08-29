package com.sparta.springprepare.content;

/**
 * chapter_episodes 의 한 행 = 수입 시 JSON 에서 뽑아 둔 에피소드 색인.
 *
 * 목적은 판정이 아니라 JOIN 이다 (schema.sql 주석). M3 의 choice_history·event_log 가
 * 복합 FK (chapter_content_id, episode_id) 로 이 테이블을 가리킨다.
 *
 * optionCount 는 NextOptions 배열의 길이다. 옵션의 내용(조건·스탯 변화)은 저장하지 않는다 —
 * 그것을 해석하는 것은 클라의 일이고, 서버가 복제하면 두 곳에서 갈린다 (PLAN 1.4).
 */
public record ChapterEpisode(
        Long chapterContentId,
        String episodeId,
        String title,
        String eventKey,
        int optionCount) {
}