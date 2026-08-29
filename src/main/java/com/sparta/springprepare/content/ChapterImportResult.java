package com.sparta.springprepare.content;

/**
 * Service -> Controller 내부 통신용 데이터
 *  (신규 201 / 이미 있음 200).
 *  */
public record ChapterImportResult(
        String chapterId,
        int version,
        int episodeCount,
        boolean created) {
}