package com.sparta.springprepare.common;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 시각의 경계 변환 (D-009).
 *
 * 이 프로젝트의 규칙은 하나다: <b>DB의 모든 절대 시각은 UTC다.</b>
 * DATETIME 컬럼에는 UTC 벽시계가 들어 있고, KST 로 바꾸는 것은 화면에 보여줄 때 클라가 한다.
 *
 * <h3>왜 변환하는 자리가 필요한가</h3>
 * MySQL Connector/J 는 DATETIME 컬럼에 대해 <b>읽기와 쓰기가 비대칭</b>이다
 * (Connector/J 개발자 가이드 "Preserving Time Instants"):
 * <ul>
 *   <li><b>쓸 때</b>: 순간 타입(OffsetDateTime, Instant)이라도 대상이 TIMESTAMP 가 아니면 변환하지 않는다.
 *       즉 {@code 2026-08-29T20:40:19+09:00} 을 그대로 넘기면 벽시계 부분인 <b>20:40:19 가 저장</b>되고,
 *       그것을 UTC 로 읽으면 9시간이 어긋난다.</li>
 *   <li><b>읽을 때</b>: 원본이 DATETIME 이고 대상이 순간 타입이면 <b>연결 시간대로 해석해 변환한다.</b>
 *       접속 URL 이 {@code connectionTimeZone=UTC} 이므로 저장된 벽시계가 그대로 UTC 순간이 된다.</li>
 * </ul>
 *
 * 그래서 규칙은:
 * <pre>
 *   DB 에 쓸 때  → LocalDateTime (UTC 벽시계). 변환 없음. 여기서 우리가 직접 정규화한다.
 *   DB 에서 읽을 때 → OffsetDateTime. 드라이버가 connectionTimeZone=UTC 로 변환한다.
 * </pre>
 *
 * 읽기가 드라이버에 의존하므로 접속 URL 이 바뀌면 조용히 틀린다.
 * 그래서 {@code SaveSlotApiTest} 가 {@code +09:00} 을 보내 UTC 로 저장·회수되는지를 직접 단언한다.
 */
public final class UtcTime {

    private UtcTime() {
    }

    /**
     * 클라가 보낸 시각을 DB 에 넣을 값으로 바꾼다.
     *
     * 오프셋이 {@code Z} 든 {@code +09:00} 이든 <b>같은 순간의 UTC 벽시계</b>로 정규화한다.
     * 클라가 규약(ISO-8601 UTC)을 어겨도 서버가 조용히 9시간 틀린 값을 저장하지 않게 하는 것이 목적이다.
     */
    public static LocalDateTime toDbValue(OffsetDateTime clientTime) {
        return clientTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
