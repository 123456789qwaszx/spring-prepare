package com.sparta.springprepare.save;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * save_slots — [2]+[3] 스냅샷의 금고. 슬롯 하나 = 클라 로컬 세이브 하나.
 */
@Repository
public class SaveSlotRepository {

    private final JdbcClient jdbc;

    public SaveSlotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * (playthrough_id, slot_no) UNIQUE 가 upsert 의 키다 — 이것이 M2 의 핵심 학습이다.
     *
     * "SELECT 해 보고 있으면 UPDATE 없으면 INSERT" 로 쓰면, 두 요청이 겹칠 때 둘 다 "없다"를 보고
     * 둘 다 INSERT 를 시도해 UNIQUE 위반이 난다. ON DUPLICATE KEY UPDATE 는 그 판정을 DB 안에서
     * 원자적으로 한다. M0 의 "UNIQUE 만이 확실한 방어선"과 같은 이야기다.
     *
     * revision 규칙에 함정이 하나 있다.
     * ON DUPLICATE KEY UPDATE 절은 **신규 INSERT 때는 실행되지 않는다.** 그래서
     *  - INSERT 값에 1 을 직접 넣고 (DEFAULT 0 을 쓰면 첫 업로드가 revision 0 이 된다)
     *  - UPDATE 절에서만 revision + 1 을 한다.
     *
     * VALUES(col) 함수를 쓰지 않는 이유: MySQL 8.0.20 에서 deprecated 다.
     * 이름 붙은 파라미터는 여러 번 참조해도 되므로 :param 을 그대로 다시 쓰면 된다.
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
    // revision 은 DB 안에서 증가했고 updated_at 은 ON UPDATE CURRENT_TIMESTAMP 가 채웠다 — 둘 다 DB 의 사실이다.
    // id 도 함께 읽는 이유: M3 의 choice_history 가 save_slot_id 로 이 슬롯을 가리켜야 하는데,
    // upsert 는 갱신 경로에서 생성 키를 주지 않으므로 재조회가 유일한 방법이다.
    private static final String SELECT_STATE = """
            SELECT id, revision, updated_at
            FROM save_slots
            WHERE playthrough_id = :playthroughId AND slot_no = :slotNo
            """;

    public Optional<SaveSlotState> findState(long playthroughId, int slotNo) {
        return jdbc.sql(SELECT_STATE)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .query(SaveSlotState.class)
                .optional();
    }

    // 목록: snapshot 을 SELECT 하지 않는다 (SaveSlotSummary 주석 참조).
    // devices 는 LEFT JOIN — device_id 가 NULL 인 슬롯이 목록에서 사라지면 안 된다.
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
