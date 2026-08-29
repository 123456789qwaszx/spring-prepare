package com.sparta.springprepare.content;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * chapter_episodes 읽기 전용 접근 (쓰기는 수입 시점의 ChapterContentRepository 가 한다).
 *
 * M3 가 두 가지를 묻는다:
 *  - 이 에피소드가 그 챕터 버전에 **실재하는가** (choices 검증)
 *  - 이 에피소드의 **EventKey 가 무엇인가** (events 는 클라가 key 를 보내지 않는다)
 * 둘 다 한 번의 조회로 답할 수 있어 메서드를 하나만 둔다.
 */
@Repository
public class ChapterEpisodeRepository {

    private final JdbcClient jdbc;

    public ChapterEpisodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // IN (:episodeIds) 는 NamedParameterJdbcTemplate 가 컬렉션 크기만큼 물음표로 펼쳐 준다.
    // JdbcClient 도 그 위에 있으므로 그대로 동작한다.
    private static final String SELECT_BY_IDS = """
            SELECT chapter_content_id, episode_id, title, event_key, option_count
            FROM chapter_episodes
            WHERE chapter_content_id = :contentId AND episode_id IN (:episodeIds)
            """;

    /**
     * 요청한 것 중 **실재하는 것만** 돌아온다. 없는 것은 결과에 빠지므로, 호출부가 크기를 비교해
     * 어느 것이 없는지 알아낸다.
     *
     * 빈 컬렉션으로 부르면 `IN ()` 이 되어 SQL 문법 오류다 — 호출부에서 막는다.
     */
    public List<ChapterEpisode> findByIds(long contentId, Collection<String> episodeIds) {
        if (episodeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_BY_IDS)
                .param("contentId", contentId)
                .param("episodeIds", episodeIds)
                .query(ChapterEpisode.class)
                .list();
    }
}
