package com.sparta.springprepare.bookmark;

import com.sparta.springprepare.common.UtcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * bookmarks — 유저 소유의 두 번째 스냅샷 (M8-A, D-021).
 *
 * <p>세이브와 달리 revision 이 없다. 낙관적 동시성은 누적되는 상태(seq 이력·스냅샷 계보)를 지키는 장치인데
 * 즐겨찾기는 누적이 없는 사본이라 지킬 것이 label·preview 뿐이고, 그것은 마지막 쓰기가 이기면 된다.
 * 그래서 upsert 가 신규/갱신 둘로만 갈리고, 갱신은 조건 없는 UPDATE 다.
 */
@Repository
public class BookmarkRepository {

    private final JdbcClient jdbc;

    public BookmarkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 존재 판정 = 삭제된 것도 포함한다 — 같은 id 로 다시 PUT 하면 부활하는 경로가 이것을 탄다. */
    private static final String SELECT_ID = """
            SELECT id FROM bookmarks WHERE user_id = :userId AND client_id = :clientId
            """;

    public Optional<Long> findId(long userId, String clientId) {
        return jdbc.sql(SELECT_ID)
                .param("userId", userId)
                .param("clientId", clientId)
                .query(Long.class)
                .optional();
    }

    private static final String INSERT = """
            INSERT INTO bookmarks
                (user_id, client_id, chapter_content_id, playthrough_id, playthrough_client_id,
                 scene_index, label, preview, snapshot, created_at)
            VALUES
                (:userId, :clientId, :chapterContentId, :playthroughId, :playthroughClientId,
                 :sceneIndex, :label, :preview, :snapshot, :createdAt)
            """;

    public long insert(long userId, String clientId, Row row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("userId", userId)
                .param("clientId", clientId)
                .param("chapterContentId", row.chapterContentId())
                .param("playthroughId", row.playthroughId())
                .param("playthroughClientId", row.playthroughClientId())
                .param("sceneIndex", row.sceneIndex())
                .param("label", row.label())
                .param("preview", row.preview())
                .param("snapshot", row.snapshotJson())
                .param("createdAt", row.createdAtDb())
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "bookmarks INSERT 가 생성 키를 돌려주지 않았다").longValue();
    }

    /**
     * 갱신 = 전부 덮고 deleted_at 을 지운다(부활). PUT 의 뜻("이 자원은 이 내용이다")을 그대로 옮긴 것이고,
     * created_at 도 클라가 준 값으로 덮는다 — 사본의 "찍은 시각"은 클라만 안다.
     */
    private static final String UPDATE = """
            UPDATE bookmarks
            SET chapter_content_id    = :chapterContentId,
                playthrough_id        = :playthroughId,
                playthrough_client_id = :playthroughClientId,
                scene_index           = :sceneIndex,
                label                 = :label,
                preview               = :preview,
                snapshot              = :snapshot,
                created_at            = :createdAt,
                deleted_at            = NULL
            WHERE id = :id
            """;

    public void update(long id, Row row) {
        jdbc.sql(UPDATE)
                .param("id", id)
                .param("chapterContentId", row.chapterContentId())
                .param("playthroughId", row.playthroughId())
                .param("playthroughClientId", row.playthroughClientId())
                .param("sceneIndex", row.sceneIndex())
                .param("label", row.label())
                .param("preview", row.preview())
                .param("snapshot", row.snapshotJson())
                .param("createdAt", row.createdAtDb())
                .update();
    }

    /** 쓰기 인자 묶음. createdAt 은 D-009 대로 UTC LocalDateTime 으로 바꿔 넘긴다 (ChoiceHistoryRepository 와 같다). */
    public record Row(long chapterContentId, Long playthroughId, String playthroughClientId, int sceneIndex,
                      String label, String preview, String snapshotJson, OffsetDateTime createdAt) {
        LocalDateTime createdAtDb() {
            return UtcTime.toDbValue(createdAt);
        }
    }

    /**
     * soft delete — 이미 지워진 것을 또 지워도 0행이고 그것은 실패가 아니다(멱등, 회차 종료와 같은 규칙).
     * 없는 id 도 0행 — 호출자는 구분하지 않는다(204).
     */
    private static final String SOFT_DELETE = """
            UPDATE bookmarks
            SET deleted_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId AND client_id = :clientId AND deleted_at IS NULL
            """;

    public int softDelete(long userId, String clientId) {
        return jdbc.sql(SOFT_DELETE)
                .param("userId", userId)
                .param("clientId", clientId)
                .update();
    }

    /**
     * 회차 되채우기의 즐겨찾기판 (D-020·D-021): 이 회차가 서버에 생기기 전에 올라온 즐겨찾기의 링크를 닫는다.
     * PlaythroughService.create 가 부른다.
     */
    private static final String BACKFILL_PLAYTHROUGH = """
            UPDATE bookmarks
            SET playthrough_id = :playthroughId
            WHERE user_id = :userId AND playthrough_client_id = :playthroughClientId AND playthrough_id IS NULL
            """;

    public int backfillPlaythrough(long userId, String playthroughClientId, long playthroughId) {
        return jdbc.sql(BACKFILL_PLAYTHROUGH)
                .param("userId", userId)
                .param("playthroughClientId", playthroughClientId)
                .param("playthroughId", playthroughId)
                .update();
    }

    // 목록: snapshot 을 SELECT 하지 않는다. 삭제된 것은 뺀다. 찍은 순서(created_at)로.
    private static final String SELECT_SUMMARIES = """
            SELECT b.client_id             AS client_bookmark_id,
                   b.label,
                   b.preview,
                   c.chapter_id,
                   c.version               AS chapter_version,
                   b.playthrough_client_id,
                   b.playthrough_id,
                   b.scene_index,
                   b.created_at,
                   b.updated_at
            FROM bookmarks b
            JOIN chapter_contents c ON c.id = b.chapter_content_id
            WHERE b.user_id = :userId AND b.deleted_at IS NULL
            ORDER BY b.created_at, b.id
            """;

    public List<BookmarkSummary> findSummaries(long userId) {
        return jdbc.sql(SELECT_SUMMARIES)
                .param("userId", userId)
                .query(BookmarkSummary.class)
                .list();
    }

    private static final String SELECT_DETAIL = """
            SELECT b.client_id             AS client_bookmark_id,
                   b.label,
                   b.preview,
                   c.chapter_id,
                   c.version               AS chapter_version,
                   b.playthrough_client_id,
                   b.playthrough_id,
                   b.scene_index,
                   b.created_at,
                   b.updated_at,
                   b.snapshot
            FROM bookmarks b
            JOIN chapter_contents c ON c.id = b.chapter_content_id
            WHERE b.user_id = :userId AND b.client_id = :clientId AND b.deleted_at IS NULL
            """;

    public Optional<BookmarkDetail> findDetail(long userId, String clientId) {
        return jdbc.sql(SELECT_DETAIL)
                .param("userId", userId)
                .param("clientId", clientId)
                .query(BookmarkDetail.class)
                .optional();
    }

    private static final String SELECT_UPSERT_RESULT = """
            SELECT client_id AS client_bookmark_id, playthrough_id, updated_at
            FROM bookmarks
            WHERE id = :id
            """;

    public BookmarkUpsertResponse findUpsertResult(long id) {
        return jdbc.sql(SELECT_UPSERT_RESULT).param("id", id).query(BookmarkUpsertResponse.class).single();
    }
}
