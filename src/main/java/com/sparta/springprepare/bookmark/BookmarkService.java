package com.sparta.springprepare.bookmark;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.NotFoundException;
import com.sparta.springprepare.common.SnapshotLimit;
import com.sparta.springprepare.content.ChapterContentRepository;
import com.sparta.springprepare.playthrough.PlaythroughRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 즐겨찾기 (M8-A, D-021). 사용자 소유, 클라 id 로 멱등 upsert, soft delete.
 *
 * <p>경로가 {@code /users/{userId}/…} 라 본인 확인은 인터셉터가 끝냈다(USERS_PATH) — 세이브의 requireOwner 같은
 * 서비스 층 소유 검증이 여기엔 없다. 사용자 존재 검증도 같은 이유로 생략한다: 토큰의 주인이 곧 그 사용자다.
 *
 * <p>세이브 upsert 와 비교해 <b>없는 것</b>: baseRevision·409·replayed·이력. 있는 것은 같다: 콘텐츠 버전 404,
 * 스냅샷 불투명, 크기 상한.
 */
@Service
public class BookmarkService {

    private static final int CLIENT_ID_MAX = 32;
    private static final int LABEL_MAX = 100;
    private static final int PREVIEW_MAX = 200;

    private final BookmarkRepository bookmarkRepository;
    private final ChapterContentRepository chapterContentRepository;
    private final PlaythroughRepository playthroughRepository;
    private final ObjectMapper objectMapper;

    public BookmarkService(BookmarkRepository bookmarkRepository,
                           ChapterContentRepository chapterContentRepository,
                           PlaythroughRepository playthroughRepository,
                           ObjectMapper objectMapper) {
        this.bookmarkRepository = bookmarkRepository;
        this.chapterContentRepository = chapterContentRepository;
        this.playthroughRepository = playthroughRepository;
        this.objectMapper = objectMapper;
    }

    public record Upserted(BookmarkUpsertResponse response, boolean created) {
    }

    /**
     * 순서: 형식 검증(400) → 콘텐츠 버전(404) → 크기(413) → 있으면 UPDATE(부활 포함)/없으면 INSERT.
     * 출처 회차의 서버 id 는 클라 id 로 찾히면 지금 채우고, 아니면 그 회차가 올 때 되채운다.
     */
    @Transactional
    public Upserted upsert(long userId, String clientBookmarkId, BookmarkUpsertRequest request) {
        requireLength(clientBookmarkId, "clientBookmarkId", CLIENT_ID_MAX, true);
        requireLength(request.label(), "label", LABEL_MAX, true);
        String preview = request.preview() == null ? "" : request.preview();
        requireLength(preview, "preview", PREVIEW_MAX, false);
        if (request.chapterId() == null || request.chapterId().isBlank()) {
            throw new BadRequestException("chapterId 이(가) 없습니다.");
        }
        if (request.chapterVersion() == null) {
            throw new BadRequestException("chapterVersion 이 없습니다.");
        }
        if (request.sceneIndex() == null || request.sceneIndex() < 0) {
            throw new BadRequestException("sceneIndex 는 0 이상이어야 합니다.");
        }
        if (request.snapshot() == null || request.snapshot().isMissingNode()) {
            throw new BadRequestException("snapshot 이 없습니다.");
        }
        if (request.playthroughClientId() != null) {
            requireLength(request.playthroughClientId(), "playthroughClientId", CLIENT_ID_MAX, true);
        }

        long chapterContentId = chapterContentRepository
                .findId(request.chapterId(), request.chapterVersion())
                .orElseThrow(() -> new NotFoundException(
                        "콘텐츠 버전이 없습니다: " + request.chapterId() + " v" + request.chapterVersion()));

        String snapshotJson = objectMapper.writeValueAsString(request.snapshot());
        SnapshotLimit.check(snapshotJson, "bookmark snapshot");

        Long playthroughId = request.playthroughClientId() == null
                ? null
                : playthroughRepository.findIdByClientId(userId, request.playthroughClientId()).orElse(null);

        OffsetDateTime createdAt = request.createdAt() != null
                ? request.createdAt()
                : OffsetDateTime.now(ZoneOffset.UTC);

        BookmarkRepository.Row row = new BookmarkRepository.Row(
                chapterContentId, playthroughId, request.playthroughClientId(), request.sceneIndex(),
                request.label(), preview, snapshotJson, createdAt);

        Optional<Long> existing = bookmarkRepository.findId(userId, clientBookmarkId);
        if (existing.isPresent()) {
            bookmarkRepository.update(existing.get(), row);
            return new Upserted(bookmarkRepository.findUpsertResult(existing.get()), false);
        }

        long id = bookmarkRepository.insert(userId, clientBookmarkId, row);
        return new Upserted(bookmarkRepository.findUpsertResult(id), true);
    }

    @Transactional(readOnly = true)
    public List<BookmarkSummary> list(long userId) {
        return bookmarkRepository.findSummaries(userId);
    }

    /** 삭제된 것은 없는 것이다 — 404. 부활은 PUT 으로만. */
    @Transactional(readOnly = true)
    public BookmarkDetail get(long userId, String clientBookmarkId) {
        return bookmarkRepository.findDetail(userId, clientBookmarkId)
                .orElseThrow(() -> new NotFoundException("즐겨찾기가 없습니다: " + clientBookmarkId));
    }

    /** 멱등 — 없어도, 이미 지웠어도 조용히 끝난다(204). 회차 종료·로그아웃과 같은 규칙. */
    @Transactional
    public void delete(long userId, String clientBookmarkId) {
        bookmarkRepository.softDelete(userId, clientBookmarkId);
    }

    private static void requireLength(String value, String field, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new BadRequestException(field + " 이(가) 없습니다.");
            }
            return;
        }
        if (value.length() > max) {
            throw new BadRequestException(field + " 은(는) " + max + "자 이하여야 합니다.");
        }
    }
}
