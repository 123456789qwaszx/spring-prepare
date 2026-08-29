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
 * [chapter_contents]
 * id = '42'
 * chapter_id = "chapter01"
 * version = 3
 * body = {...}
 *
 * [chapter_episodes]
 * '42' / ep001
 * '42' / ep002
 * '42' / ep003
 */
@Repository
public class ChapterContentRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public ChapterContentRepository(JdbcClient jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    private static final String SELECT_BY_CHECKSUM = """
            SELECT id, chapter_id, version, display_name, start_episode_id, checksum, imported_at
            FROM chapter_contents
            WHERE checksum = :checksum
            """;

    /** 같은 바이트의 파일이 이미 있으면,그 행을 그대로 반환. */
    public Optional<ChapterContent> findByChecksum(String checksum) {
        return jdbc.sql(SELECT_BY_CHECKSUM)
                .param("checksum", checksum)
                .query(ChapterContent.class)
                .optional();
    }


    // COALESCE로 첫 수입(행 없음)일 때의 NULL을 0으로.
    private static final String NEXT_VERSION = """
            SELECT COALESCE(MAX(version), 0) + 1
            FROM chapter_contents
            WHERE chapter_id = :chapterId
            """;

    /** 이 조회와 아래 INSERT 사이의 경쟁은 무시 */
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

    /** imported_at은 넣지 않음 - DB DEFAULT 사용. */
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
     * 루프로 한 건씩 보내도 되지만 batchUpdate 를 쓰는 이유:
     * - 왕복 횟수를 절감.
     * - 여러 건을 한 덩어리로 쓴다는 의도 명시.
     */
    public void insertEpisodes(List<ChapterEpisode> episodes) {
        if (episodes.isEmpty())
            return;

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


    // 챕터마다 최신 버전 한 줄. 서브쿼리로 (chapter_id, MAX(version)) 를 구해 자기 자신과 JOIN.
    // "GROUP BY 한 결과의 다른 컬럼"을 얻기 위함.
    // MySQL 은 ONLY_FULL_GROUP_BY 에서 SELECT display_name ... GROUP BY chapter_id 를 거부함.
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

    /** 원본 JSON 을 문자열 그대로. */
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
     * 세이브는 "어느 버전의 챕터에서 만들어졌는가"를 가리켜야 하고,
     * 서비스가 이것으로 먼저 확인해야 FK 위반(500) 대신 404 를 낼 수 있다
     */
    public Optional<Long> findId(String chapterId, int version) {
        return jdbc.sql(SELECT_ID)
                .param("chapterId", chapterId)
                .param("version", version)
                .query(Long.class)
                .optional();
    }
}