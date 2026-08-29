package com.sparta.springprepare.save;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * devices — 어느 기기에서 올린 세이브인지. 충돌 해소(M4)의 근거가 된다.
 *
 * 클라가 만든 설치 고유값(device_key)으로 upsert 한다. 사용자가 기기를 등록하는 화면 같은 것은 없다 —
 * 세이브를 올리는 순간 부수적으로 등록된다.
 */
@Repository
public class DeviceRepository {

    private final JdbcClient jdbc;

    public DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // (user_id, device_key) UNIQUE 가 upsert 의 키다. 이미 있으면 마지막 접속 시각만 갱신한다.
    private static final String UPSERT = """
            INSERT INTO devices (user_id, device_key)
            VALUES (:userId, :deviceKey)
            ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP
            """;

    private static final String SELECT_ID = """
            SELECT id FROM devices WHERE user_id = :userId AND device_key = :deviceKey
            """;

    /**
     * upsert 후 id 를 돌려준다.
     *
     * 문장이 둘인 이유: MySQL 에는 ON DUPLICATE KEY UPDATE 절에서 id = LAST_INSERT_ID(id) 를 써서
     * 갱신 경로에서도 생성 키 채널로 id 를 받아 내는 트릭이 있다. 한 번에 끝나지만,
     * "왜 자기 자신을 대입하는가"를 모르면 읽을 수 없는 코드가 된다.
     * 여기서는 upsert 와 조회를 나눠 쓴다 — 같은 트랜잭션·같은 커넥션이라 결과는 같고, 읽으면 바로 이해된다.
     * (성능이 문제가 되는 자리가 아니다. 세이브 업로드마다 SELECT 한 번이다.)
     */
    public long upsert(long userId, String deviceKey) {
        jdbc.sql(UPSERT)
                .param("userId", userId)
                .param("deviceKey", deviceKey)
                .update();

        return jdbc.sql(SELECT_ID)
                .param("userId", userId)
                .param("deviceKey", deviceKey)
                .query(Long.class)
                .single();
    }
}