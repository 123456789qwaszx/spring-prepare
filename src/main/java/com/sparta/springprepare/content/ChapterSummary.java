package com.sparta.springprepare.content;

/** GET /content/chapters 의 한 줄. 챕터마다 최신 버전 하나. */
public record ChapterSummary(String chapterId, Integer latestVersion, String displayName) {
}