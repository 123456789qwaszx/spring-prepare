package com.sparta.springprepare.content;

import java.time.OffsetDateTime;

/** GET /content/chapters/{chapterId}/versions 의 한 줄. */
public record ChapterVersionInfo(Integer version, OffsetDateTime importedAt, String checksum) {
}
