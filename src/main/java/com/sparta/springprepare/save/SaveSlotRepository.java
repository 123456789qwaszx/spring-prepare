package com.sparta.springprepare.save;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * save_slots - [2]+[3] 스냅샷의 금고. 슬롯 하나 = 클라 로컬 세이브 하나.
 */
@Repository
public class SaveSlotRepository {

    private final JdbcClient jdbc;

    public SaveSlotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * (playthrough_id, slot_no) UNIQUE 가 upsert 의 키.
     *
     * "SELECT 해 보고 있으면 UPDATE 없으면 INSERT" 로 쓰면,
     * 두 요청이 겹칠 때 둘 다 "없다"를 보고 둘 다 INSERT 를 시도해 UNIQUE 위반이 난다.
     * ON DUPLICATE KEY UPDATE 는 그 판정을 DB 안에서 원자적으로 한다.
     */
    private static final String UPSERT = """
            INSERT INTO save_slots
                (playthrough_id, slot_no, chapter_content_id, current_episode_id,
                 snapshot, revision, play_seconds, device_id)
            VALUES
                (:playthroughId, :slotNo, :chapterContentId, :currentEpisodeId,
                 :snapshot, 1, :playSeconds, :deviceId)
            ON DUPLICATE KEY UPDATE
                chapter_content_id = :chapterContentId,
                current_episode_id = :currentEpisodeId,
                snapshot           = :snapshot,
                play_seconds       = :playSeconds,
                device_id          = :deviceId,
                revision           = revision + 1
            """;

    public void upsert(long playthroughId, int slotNo, long chapterContentId,
                       String currentEpisodeId, String snapshotJson, int playSeconds, Long deviceId) {
        jdbc.sql(UPSERT)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .param("chapterContentId", chapterContentId)
                .param("currentEpisodeId", currentEpisodeId)
                .param("snapshot", snapshotJson)
                .param("playSeconds", playSeconds)
                .param("deviceId", deviceId)     // null 허용 — deviceKey 를 안 보낸 경우
                .update();
    }

    // upsert 결과를 앱이 계산하지 않고 DB 에서 다시 읽는다.
    // revision 은 DB 안에서 증가했고 updated_at 은 ON UPDATE CURRENT_TIMESTAMP 사용.
    private static final String SELECT_STATE = """
            SELECT revision, updated_at
            FROM save_slots
            WHERE playthrough_id = :playthroughId AND slot_no = :slotNo
            """;

    public Optional<SaveUploadResponse> findState(long playthroughId, int slotNo) {
        return jdbc.sql(SELECT_STATE)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .query(SaveUploadResponse.class)
                .optional();
    }

    // 목록: snapshot 을 SELECT 하지 않는다 (SaveSlotSummary 주석 참조).
    // devices 는 LEFT JOIN — device_id 가 NULL 인 슬롯이 목록에서 사라지면 안 됨.
    private static final String SELECT_SUMMARIES = """
            SELECT s.slot_no,
                   c.chapter_id,
                   c.version AS chapter_version,
                   s.current_episode_id,
                   s.revision,
                   s.play_seconds,
                   s.updated_at,
                   d.device_key AS device
            FROM save_slots s
            JOIN chapter_contents c ON c.id = s.chapter_content_id
            LEFT JOIN devices d     ON d.id = s.device_id
            WHERE s.playthrough_id = :playthroughId
            ORDER BY s.slot_no
            """;

    public List<SaveSlotSummary> findSummaries(long playthroughId) {
        return jdbc.sql(SELECT_SUMMARIES)
                .param("playthroughId", playthroughId)
                .query(SaveSlotSummary.class)
                .list();
    }

    private static final String SELECT_DETAIL = """
            SELECT s.slot_no,
                   c.chapter_id,
                   c.version AS chapter_version,
                   s.current_episode_id,
                   s.revision,
                   s.play_seconds,
                   s.updated_at,
                   d.device_key AS device,
                   s.snapshot
            FROM save_slots s
            JOIN chapter_contents c ON c.id = s.chapter_content_id
            LEFT JOIN devices d     ON d.id = s.device_id
            WHERE s.playthrough_id = :playthroughId AND s.slot_no = :slotNo
            """;

    public Optional<SaveSlotDetail> findDetail(long playthroughId, int slotNo) {
        return jdbc.sql(SELECT_DETAIL)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .query(SaveSlotDetail.class)
                .optional();
    }
}