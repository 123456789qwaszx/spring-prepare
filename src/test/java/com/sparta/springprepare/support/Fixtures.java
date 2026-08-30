package com.sparta.springprepare.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 테스트 준비용 데이터 삽입.
 *
 * API 를 거치지 않고 SQL 로 직접 넣는다. 준비 단계까지 API 로 하면 그 API 가 깨졌을 때
 * 무관한 테스트가 무더기로 빨개져서 원인을 찾기 어려워진다.
 * (M1 의 수입 API 자체를 검증하는 것은 ChapterContentApiTest 의 일이다.)
 */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * 픽스처 사용자의 비밀번호 원문. AuthSupport 가 이 값으로 로그인한다 (M6).
     */
    public static final String TEST_PASSWORD = "test-only";

    /**
     * TEST_PASSWORD 의 BCrypt 해시 (M6-3 이후 users.password 는 해시다).
     * cost 4 로 미리 계산해 박아 둔 값 — 검증(matches)은 해시 문자열 안의 cost 를 따르므로
     * 앱의 strength 설정과 무관하게 항상 맞는다. 낮은 cost 인 이유는 테스트 로그인 속도뿐이다.
     */
    private static final String TEST_PASSWORD_HASH =
            "$2a$04$4z9I7SMEKFgfBtFpoWsEYOZ.a9aZvYZQmkjkona//DqZRJgh/dJ1C";

    public static long insertUser(JdbcClient jdbc, String username) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (username, password) VALUES (:username, :passwordHash)")
                .param("username", username)
                .param("passwordHash", TEST_PASSWORD_HASH)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * chapter_contents 한 행. body 는 최소 형태의 유효한 JSON 이면 된다 —
     * M2 는 콘텐츠의 내용을 전혀 보지 않고 id 만 쓴다.
     * checksum 은 UNIQUE 이므로 (chapterId, version) 으로 서로 다른 값을 만든다.
     */
    public static long insertChapter(JdbcClient jdbc, String chapterId, int version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO chapter_contents
                            (chapter_id, version, display_name, start_episode_id, body, checksum)
                        VALUES (:chapterId, :version, :chapterId, 'EP01',
                                JSON_OBJECT('ChapterId', :chapterId), :checksum)
                        """)
                .param("chapterId", chapterId)
                .param("version", version)
                // 64자 고정 길이 CHAR(64) 를 채우기 위한 더미. 실제 SHA-256 일 필요는 없다.
                .param("checksum", String.format("%-64s", chapterId + "-v" + version).replace(' ', '0'))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public static long insertPlaythrough(JdbcClient jdbc, long userId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO playthroughs (user_id) VALUES (:userId)")
                .param("userId", userId)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * chapter_episodes 한 행 (M3). 색인은 원래 수입 시점에 생기지만,
     * M3 의 테스트가 확인하려는 것은 수입이 아니라 **이력 기록**이다.
     *
     * <p>eventKey 를 빈 문자열로 넣는 것과 값으로 넣는 것이 곧 두 갈래의 테스트다 —
     * 빈 것에 이벤트를 걸면 400, 값이 있으면 그 값이 event_log 에 그대로 들어간다.
     * (수입 → 색인 → 이벤트 전 구간은 별도로 `qwer-events.progression.json` 을 API 로 올려 확인한다.)
     *
     * <p>키가 (chapter_content_id, episode_id) 복합 PK 라 생성 키가 없다 — 반환값도 없다.
     */
    public static void insertEpisode(JdbcClient jdbc, long chapterContentId,
                                     String episodeId, String eventKey) {
        jdbc.sql("""
                        INSERT INTO chapter_episodes
                            (chapter_content_id, episode_id, title, event_key, option_count)
                        VALUES (:contentId, :episodeId, :episodeId, :eventKey, 0)
                        """)
                .param("contentId", chapterContentId)
                .param("episodeId", episodeId)
                .param("eventKey", eventKey)
                .update();
    }
}
