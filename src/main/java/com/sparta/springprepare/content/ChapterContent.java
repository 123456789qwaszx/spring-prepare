package com.sparta.springprepare.content;

import java.time.LocalDateTime;

/**
 * chapter_contents 의 한 행.
 *
 * body 를 여기 넣지 않은 이유:
 * - Json 크기 및, 대부분 조회에서 필요 없음.
 * "행 = record" 규약을 지키되 큰 컬럼은 별도 쿼리로. 필요한 데이터만 명시적 선택할 것.
 */
public record ChapterContent(
        Long id,
        String chapterId,
        Integer version,
        String displayName,
        String startEpisodeId,
        String checksum,
        LocalDateTime importedAt) {
}