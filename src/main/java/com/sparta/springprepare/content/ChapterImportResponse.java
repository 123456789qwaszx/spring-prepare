package com.sparta.springprepare.content;

/**
 * 외부 API에 보낼 형식.
 * POST /content/chapters 응답 본문.
 * */
public record ChapterImportResponse(
        String chapterId,
        int version,
        int episodeCount) {

    static ChapterImportResponse from(ChapterImportResult result) {
        return new ChapterImportResponse(
                result.chapterId(),
                result.version(),
                result.episodeCount());
    }
}