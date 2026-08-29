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

    public static long insertUser(JdbcClient jdbc, String username) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (username, password) VALUES (:username, 'test-only')")
                .param("username", username)
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
}
