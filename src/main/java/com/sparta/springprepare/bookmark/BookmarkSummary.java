package com.sparta.springprepare.bookmark;

import java.time.OffsetDateTime;

/**
 * GET /users/{userId}/bookmarks 의 한 줄 — <b>메타만</b>, 스냅샷 없음 (세이브 목록과 같은 결, F22).
 * 복구 화면이 목록을 그리고, 고른 것만 {@link BookmarkDetail} 로 내려받는다.
 */
public record BookmarkSummary(
        String clientBookmarkId,
        String label,
        String preview,
        String chapterId,
        Integer chapterVersion,
        String playthroughClientId,
        Long playthroughId,
        Integer sceneIndex,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
