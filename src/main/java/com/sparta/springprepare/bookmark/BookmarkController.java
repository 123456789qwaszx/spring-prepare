package com.sparta.springprepare.bookmark;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 즐겨찾기 API (M8-A, D-021). 전부 {@code /users/{userId}/bookmarks} 아래 — 인터셉터의 본인 확인이 그대로 걸린다.
 *
 * <pre>
 *   PUT    /{clientBookmarkId}   201 신규 / 200 갱신·부활     (멱등 upsert)
 *   GET                          메타 목록 (스냅샷 없음)
 *   GET    /{clientBookmarkId}   메타 + 스냅샷 (복구용)
 *   DELETE /{clientBookmarkId}   204 (soft, 멱등)
 * </pre>
 */
@RestController
@RequestMapping("/users/{userId}/bookmarks")
public class BookmarkController {

    private final BookmarkService service;

    public BookmarkController(BookmarkService service) {
        this.service = service;
    }

    @PutMapping("/{clientBookmarkId}")
    public ResponseEntity<BookmarkUpsertResponse> upsert(@PathVariable long userId,
                                                         @PathVariable String clientBookmarkId,
                                                         @RequestBody BookmarkUpsertRequest request) {
        BookmarkService.Upserted result = service.upsert(userId, clientBookmarkId, request);
        if (!result.created()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity
                .created(URI.create("/users/" + userId + "/bookmarks/" + clientBookmarkId))
                .body(result.response());
    }

    @GetMapping
    public List<BookmarkSummary> list(@PathVariable long userId) {
        return service.list(userId);
    }

    @GetMapping("/{clientBookmarkId}")
    public BookmarkDetail get(@PathVariable long userId, @PathVariable String clientBookmarkId) {
        return service.get(userId, clientBookmarkId);
    }

    @DeleteMapping("/{clientBookmarkId}")
    public ResponseEntity<Void> delete(@PathVariable long userId, @PathVariable String clientBookmarkId) {
        service.delete(userId, clientBookmarkId);
        return ResponseEntity.noContent().build();
    }
}
