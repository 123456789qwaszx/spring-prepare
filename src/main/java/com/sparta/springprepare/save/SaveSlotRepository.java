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
     * <h3>M2 의 upsert 를 M4 에서 둘로 갈랐다 — 그 이유가 이 클래스의 핵심이다</h3>
     *
     * M2 는 {@code INSERT … ON DUPLICATE KEY UPDATE} 하나로 신규·갱신을 다 처리했다. 짧고, 원자적이고,
     * "UNIQUE 만이 확실한 방어선"이라는 M0 의 교훈과도 맞았다. 그런데 그 문장은 <b>항상 성공한다.</b>
     * 두 기기가 동시에 쓰면 둘 다 200 을 받고 나중 것이 조용히 이긴다 —
     * 데이터가 사라지는 게 아니라 <b>사라졌다는 사실이 사라진다</b> ({@code SaveSlotConcurrencyTest}).
     *
     * 그래서 M4 는 경로를 나눈다:
     * <pre>
     *   슬롯이 없다 → INSERT            (revision 1 로 시작)
     *   슬롯이 있다 → 조건부 UPDATE     (WHERE revision = :baseRevision, 0행이면 충돌)
     * </pre>
     *
     * 곁가지 이득 하나: M2 F18 에서 본 <b>AUTO_INCREMENT 낭비가 사라진다.</b>
     * {@code ON DUPLICATE KEY UPDATE} 는 갱신 경로로 가도 id 를 하나 할당했다가 버렸다(devices 가 1, 3 이었던 이유).
     * INSERT 를 신규일 때만 하면 그 일이 없다. 다만 이건 부수 효과이고, 주된 이유는 여전히 충돌 감지다.
     */
    private static final String INSERT = """
            INSERT INTO save_slots
                (playthrough_id, slot_no, chapter_content_id, current_episode_id,
                 snapshot, revision, play_seconds, device_id,
                 inherited_play_seconds, own_play_seconds, chapter_completed)
            VALUES
                (:playthroughId, :slotNo, :chapterContentId, :currentEpisodeId,
                 :snapshot, 1, :playSeconds, :deviceId,
                 :inheritedPlaySeconds, :ownPlaySeconds, :chapterCompleted)
            """;

    /** M8-A 가 더한 열 셋을 한 묶음으로 나른다 — insert·update 의 인자 목록이 열 개를 넘지 않게. */
    public record Extras(int inheritedPlaySeconds, int ownPlaySeconds, boolean chapterCompleted) {
    }

    /**
     * revision 에 1 을 직접 넣는다. 컬럼 DEFAULT 는 0 이라 그것에 맡기면 첫 업로드가 revision 0 이 된다
     * (M2 부터의 규칙 — 클라가 받은 첫 revision 은 1 이다).
     *
     * 여기서 UNIQUE {@code (playthrough_id, slot_no)} 위반이 날 수 있다: 두 기기가 같은 슬롯을
     * <b>동시에 처음</b> 만드는 경우다. 그때는 DuplicateKeyException → 409 DUPLICATE 로 나간다.
     * 충돌(CONFLICT)이 아니라 중복(DUPLICATE)으로 보이는 것이 메시지상 아쉽지만, 막아야 할 것은 막았다.
     * 다듬는 것은 M6 (에러 형식 통일)의 몫으로 둔다.
     */
    public void insert(long playthroughId, int slotNo, long chapterContentId,
                       String currentEpisodeId, String snapshotJson, int playSeconds, Long deviceId,
                       Extras extras) {
        jdbc.sql(INSERT)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .param("chapterContentId", chapterContentId)
                .param("currentEpisodeId", currentEpisodeId)
                .param("snapshot", snapshotJson)
                .param("playSeconds", playSeconds)
                .param("deviceId", deviceId)     // null 허용 — deviceKey 를 안 보낸 경우
                .param("inheritedPlaySeconds", extras.inheritedPlaySeconds())
                .param("ownPlaySeconds", extras.ownPlaySeconds())
                .param("chapterCompleted", extras.chapterCompleted())
                .update();
    }

    /**
     * <b>낙관적 동시성의 전부가 이 한 문장이다.</b>
     *
     * <pre>
     *   WHERE … AND revision = :baseRevision
     * </pre>
     *
     * 영향 행 수가 1 이면 "내가 알던 상태 그대로였다", 0 이면 "그 사이 누군가 바꿨다"다.
     * 읽고 나서 비교하는 것이 아니라 <b>읽기와 쓰기가 한 문장 안에서 일어나므로</b> 그 사이에 끼어들 틈이 없다.
     * {@code SELECT} 로 revision 을 확인한 뒤 {@code UPDATE} 하면 그 둘 사이가 바로 틈이 된다.
     *
     * <h3>왜 락을 안 쓰나</h3>
     * {@code SELECT … FOR UPDATE} 로도 되지만, 충돌은 드물고 락은 커넥션을 붙든다. 무엇보다
     * <b>락 없이도 정확하다</b>는 것을 테스트가 증명한다 (PLAN M4 "락이 아니라 데이터로 푼다").
     *
     * <h3>REPEATABLE READ 에서 안전한 이유</h3>
     * UPDATE 의 WHERE 는 트랜잭션 시작 시점의 스냅샷이 아니라 <b>최신 커밋본</b>을 읽고 행 락을 잡는다
     * (current read). 그래서 두 트랜잭션 중 정확히 하나만 {@code revision = base} 를 만족한다.
     * 조회로 읽은 값과 UPDATE 가 보는 값이 다를 수 있다는 뜻이기도 하다 — 그래서 판정을 조회가 아니라
     * <b>UPDATE 의 반환값</b>으로 한다.
     *
     * @return 영향 받은 행 수. 0 이면 충돌.
     *         (Connector/J 의 {@code useAffectedRows} 설정에 따라 "일치한 행"과 "바뀐 행"이 갈리지만,
     *          {@code revision = revision + 1} 이 항상 값을 바꾸므로 여기서는 두 해석이 같은 수를 준다.
     *          설정에 기대지 않는 편이 안전하다.)
     */
    private static final String UPDATE_IF_REVISION = """
            UPDATE save_slots
            SET chapter_content_id     = :chapterContentId,
                current_episode_id     = :currentEpisodeId,
                snapshot               = :snapshot,
                play_seconds           = :playSeconds,
                device_id              = :deviceId,
                inherited_play_seconds = :inheritedPlaySeconds,
                own_play_seconds       = :ownPlaySeconds,
                chapter_completed      = :chapterCompleted,
                revision               = revision + 1
            WHERE playthrough_id = :playthroughId
              AND slot_no        = :slotNo
              AND revision       = :baseRevision
            """;

    public int updateIfRevision(long playthroughId, int slotNo, long baseRevision, long chapterContentId,
                                String currentEpisodeId, String snapshotJson, int playSeconds, Long deviceId,
                                Extras extras) {
        return jdbc.sql(UPDATE_IF_REVISION)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .param("baseRevision", baseRevision)
                .param("chapterContentId", chapterContentId)
                .param("currentEpisodeId", currentEpisodeId)
                .param("snapshot", snapshotJson)
                .param("playSeconds", playSeconds)
                .param("deviceId", deviceId)
                .param("inheritedPlaySeconds", extras.inheritedPlaySeconds())
                .param("ownPlaySeconds", extras.ownPlaySeconds())
                .param("chapterCompleted", extras.chapterCompleted())
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
                   s.inherited_play_seconds,
                   s.own_play_seconds,
                   s.chapter_completed,
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

    // 충돌 응답에 실을 "지금 서버는 이렇다" (M4). 목록 쿼리와 같은 컬럼이지만 슬롯 하나만 본다.
    // 스냅샷은 넣지 않는다 — 충돌 UI 가 보여줄 것은 "누가 언제 어디까지" 이지 세이브 내용 전체가 아니다.
    private static final String SELECT_SUMMARY_ONE = """
            SELECT s.slot_no,
                   c.chapter_id,
                   c.version AS chapter_version,
                   s.current_episode_id,
                   s.revision,
                   s.play_seconds,
                   s.inherited_play_seconds,
                   s.own_play_seconds,
                   s.chapter_completed,
                   s.updated_at,
                   d.device_key AS device
            FROM save_slots s
            JOIN chapter_contents c ON c.id = s.chapter_content_id
            LEFT JOIN devices d     ON d.id = s.device_id
            WHERE s.playthrough_id = :playthroughId AND s.slot_no = :slotNo
            """;

    public Optional<SaveSlotSummary> findSummary(long playthroughId, int slotNo) {
        return jdbc.sql(SELECT_SUMMARY_ONE)
                .param("playthroughId", playthroughId)
                .param("slotNo", slotNo)
                .query(SaveSlotSummary.class)
                .optional();
    }

    private static final String SELECT_DETAIL = """
            SELECT s.slot_no,
                   c.chapter_id,
                   c.version AS chapter_version,
                   s.current_episode_id,
                   s.revision,
                   s.play_seconds,
                   s.inherited_play_seconds,
                   s.own_play_seconds,
                   s.chapter_completed,
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
