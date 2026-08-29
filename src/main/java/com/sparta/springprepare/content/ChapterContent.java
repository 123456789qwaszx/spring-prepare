package com.sparta.springprepare.content;

import java.time.OffsetDateTime;

/**
 * chapter_contents 의 한 행 — 단, body 는 뺐다.
 *
 * body 를 여기 넣지 않은 이유: 원본 JSON 은 수십 KB가 될 수 있고 목록·조회의 대부분은 body 가 필요 없다.
 * "행 = record" 규약을 지키되 큰 컬럼은 별도 쿼리로 가져온다. SELECT 컬럼 목록을 손으로 쓰는 습관과 같은 이유다.
 */
public record ChapterContent(
        Long id,
        String chapterId,
        Integer version,
        String displayName,
        String startEpisodeId,
        String checksum,
        OffsetDateTime importedAt) {
}
