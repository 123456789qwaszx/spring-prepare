package com.sparta.springprepare.bookmark;

import java.time.OffsetDateTime;

/** PUT 응답. 201(신규)·200(갱신·부활) 모두 이 모양 — 상태 코드가 가른다. */
public record BookmarkUpsertResponse(String clientBookmarkId, Long playthroughId, OffsetDateTime updatedAt) {
}
