package com.sparta.springprepare.bookmark;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.OffsetDateTime;

/** GET /users/{userId}/bookmarks/{clientBookmarkId} — 메타 + 스냅샷 통째(열지 않고 되돌린다, SaveSlotDetail 과 같다). */
public record BookmarkDetail(
        String clientBookmarkId,
        String label,
        String preview,
        String chapterId,
        Integer chapterVersion,
        String playthroughClientId,
        Long playthroughId,
        Integer sceneIndex,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @JsonRawValue String snapshot) {
}
