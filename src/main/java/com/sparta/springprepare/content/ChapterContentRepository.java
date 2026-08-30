package com.sparta.springprepare.content;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * chapter_contents / chapter_episodes 접근.
 *
 * 이 레포지토리는 JDBC 진입점을 **둘** 주입받는다.
 *  - JdbcClient              : 단건 조회·INSERT. M0 와 같다.
 *  - NamedParameterJdbcTemplate : 배치 INSERT. JdbcClient 는 배치를 지원하지 않는다 (PLAN M3 함정).
 * 둘 다 같은 DataSource 를 쓰고, 스레드에 바인딩된 같은 커넥션을 얻는다(DataSourceUtils).
 * 그래서 배치도 Service 가 연 같은 트랜잭션 안에서 함께 롤백된다.
 */
@Repository
public class ChapterContentRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public ChapterContentRepository(JdbcClient jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    // ── 수입 ────────────────────────────────────────────────────────────

    private static final String SELECT_BY_CHECKSUM = """
            SELECT id, chapter_id, version, display_name, start_episode_id, checksum, imported_at
            FROM chapter_contents
            WHERE checksum = :checksum
            """;

    /** 같은 바이트의 파일이 이미 있는가. 있으면 재수입하지 않고 그 행을 돌려준다(멱등). */
    public Optional<ChapterContent> findByChecksum(String checksum) {
        return jdbc.sql(SELECT_BY_CHECKSUM)
                .param("checksum", checksum)
                .query(ChapterContent.class)
                .optional();
    }

    // COALESCE 로 첫 수입(행 없음)일 때의 NULL 을 0 으로 접는다 → 항상 1행이 돌아와 single() 이 안전하다.
    private static final String NEXT_VERSION = """
            SELECT COALESCE(MAX(version), 0) + 1
            FROM chapter_contents
            WHERE chapter_id = :chapterId
            """;

    /**
     * 이 조회와 아래 INSERT 사이의 경쟁은 무시한다 — 수입은 개발자 한 명이 수동으로 하는 작업이다 (PLAN M1 함정).
     * 그래도 (chapter_id, version) UNIQUE 가 안전망으로 남아 있어, 만에 하나 겹치면 409 로 끝나고 데이터는 깨지지 않는다.
     * "알고 무시하는 것"과 "모르고 지나치는 것"은 다르다.
     */
    public int nextVersion(String chapterId) {
        return jdbc.sql(NEXT_VERSION)
                .param("chapterId", chapterId)
                .query(Integer.class)
                .single();
    }

    private static final String INSERT_CONTENT = """
            INSERT INTO chapter_contents
                (chapter_id, version, display_name, start_episode_id, body, checksum)
            VALUES (:chapterId, :version, :displayName, :startEpisodeId, :body, :checksum)
            """;

    /** imported_at 은 넣지 않는다 — DB DEFAULT 가 채운다 (M0 와 같은 규칙). */
    public long insertContent(String chapterId, int version, String displayName,
                              String startEpisodeId, String body, String checksum) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql(INSERT_CONTENT)
                .param("chapterId", chapterId)
                .param("version", version)
                .param("displayName", displayName)
                .param("startEpisodeId", startEpisodeId)
                .param("body", body)          // MySQL 이 JSON 으로 파싱한다. 잘못된 JSON 이면 여기서 거부된다.
                .param("checksum", checksum)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "chapter_contents INSERT 가 생성 키를 돌려주지 않았다")
                .longValue();
    }

    private static final String INSERT_EPISODE = """
            INSERT INTO chapter_episodes
                (chapter_content_id, episode_id, title, event_key, option_count)
            VALUES (:chapterContentId, :episodeId, :title, :eventKey, :optionCount)
            """;

    /**
     * 색인 배치 INSERT.
     *
     * 루프로 한 건씩 보내도 되지만 batchUpdate 를 쓰는 이유는 두 가지다.
     *  (1) 왕복 횟수를 줄인다. 노드가 수백 개인 챕터에서 차이가 난다.
     *  (2) "여러 건을 한 덩어리로 쓴다"는 의도가 코드에 드러난다.
     * 다만 MySQL Connector/J 는 기본적으로 이를 개별 문장으로 보낸다 —
     * 진짜 한 문장으로 묶으려면 접속 URL 에 rewriteBatchedStatements=true 가 필요하다. 지금 규모에선 불필요.
     *
     * 빈 리스트로 부르지 않는다 — 드라이버에 따라 빈 배치가 예외가 된다.
     */
    public void insertEpisodes(List<ChapterEpisode> episodes) {
        if (episodes.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = episodes.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("chapterContentId", e.chapterContentId())
                        .addValue("episodeId", e.episodeId())
                        .addValue("title", e.title())
                        .addValue("eventKey", e.eventKey())
                        .addValue("optionCount", e.optionCount()))
                .toArray(SqlParameterSource[]::new);
        namedJdbc.batchUpdate(INSERT_EPISODE, batch);
    }

    private static final String COUNT_EPISODES = """
            SELECT COUNT(*) FROM chapter_episodes WHERE chapter_content_id = :contentId
            """;

    public int countEpisodes(long contentId) {
        return jdbc.sql(COUNT_EPISODES)
                .param("contentId", contentId)
                .query(Integer.class)
                .single();
    }

    // ── 조회 ────────────────────────────────────────────────────────────

    // 챕터마다 최신 버전 한 줄. 서브쿼리로 (chapter_id, MAX(version)) 를 구해 자기 자신과 JOIN 한다.
    // "GROUP BY 한 결과의 다른 컬럼"을 얻는 표준적인 방법이다 — MySQL 은 ONLY_FULL_GROUP_BY 에서
    // SELECT display_name ... GROUP BY chapter_id 를 거부하므로 이 형태가 필요하다.
    private static final String SELECT_SUMMARIES = """
            SELECT c.chapter_id, c.version AS latest_version, c.display_name
            FROM chapter_contents c
            JOIN (SELECT chapter_id, MAX(version) AS max_version
                  FROM chapter_contents
                  GROUP BY chapter_id) m
              ON c.chapter_id = m.chapter_id AND c.version = m.max_version
            ORDER BY c.chapter_id
            """;

    public List<ChapterSummary> findSummaries() {
        return jdbc.sql(SELECT_SUMMARIES).query(ChapterSummary.class).list();
    }

    private static final String SELECT_VERSIONS = """
            SELECT version, imported_at, checksum
            FROM chapter_contents
            WHERE chapter_id = :chapterId
            ORDER BY version
            """;

    public List<ChapterVersionInfo> findVersions(String chapterId) {
        return jdbc.sql(SELECT_VERSIONS)
                .param("chapterId", chapterId)
                .query(ChapterVersionInfo.class)
                .list();
    }

    private static final String SELECT_BODY = """
            SELECT body FROM chapter_contents
            WHERE chapter_id = :chapterId AND version = :version
            """;

    /** 원본 JSON 을 문자열 그대로. 서버는 이 내용을 해석하지 않는다 (PLAN 1.4). */
    public Optional<String> findBody(String chapterId, int version) {
        return jdbc.sql(SELECT_BODY)
                .param("chapterId", chapterId)
                .param("version", version)
                .query(String.class)
                .optional();
    }

    private static final String SELECT_LATEST_BODY = """
            SELECT body FROM chapter_contents
            WHERE chapter_id = :chapterId
            ORDER BY version DESC
            LIMIT 1
            """;

    public Optional<String> findLatestBody(String chapterId) {
        return jdbc.sql(SELECT_LATEST_BODY)
                .param("chapterId", chapterId)
                .query(String.class)
                .optional();
    }

    private static final String SELECT_ID = """
            SELECT id FROM chapter_contents
            WHERE chapter_id = :chapterId AND version = :version
            """;

    /**
     * M2 가 쓸 메서드를 미리 둔다 — 세이브는 "어느 버전의 챕터에서 만들어졌는가"를 가리켜야 하고,
     * 서비스가 이것으로 먼저 확인해야 FK 위반(500) 대신 404 를 낼 수 있다 (M1 계획서 §9).
     */
    public Optional<Long> findId(String chapterId, int version) {
        return jdbc.sql(SELECT_ID)
                .param("chapterId", chapterId)
                .param("version", version)
                .query(Long.class)
                .optional();
    }

    // M5. 집계 API 가 version 을 생략했을 때 최신 버전을 고른다.
    //
    // MAX(version) 을 쓰지 않는 이유: 행이 하나도 없어도 집계 함수는 **한 행(NULL)** 을 돌려준다.
    // 그러면 .optional() 이 "없음" 이 아니라 "NULL 이 하나 있음" 을 보게 된다.
    // ORDER BY … LIMIT 1 은 행이 없으면 정말로 0행이라 Optional.empty() 가 된다 —
    // "없다" 를 표현하는 데는 이쪽이 정직하다.
    private static final String SELECT_LATEST_VERSION = """
            SELECT version FROM chapter_contents
            WHERE chapter_id = :chapterId
            ORDER BY version DESC
            LIMIT 1
            """;

    public Optional<Integer> findLatestVersion(String chapterId) {
        return jdbc.sql(SELECT_LATEST_VERSION)
                .param("chapterId", chapterId)
                .query(Integer.class)
                .optional();
    }
}
