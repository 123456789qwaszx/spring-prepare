package com.sparta.springprepare.content;

import java.time.LocalDateTime;

/** GET /content/chapters/{chapterId}/versions 의 한 줄. */
public record ChapterVersionInfo(Integer version, LocalDateTime importedAt, String checksum) {
}