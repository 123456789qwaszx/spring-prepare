package com.sparta.springprepare.playthrough;

import java.time.OffsetDateTime;

/**
 * playthroughs 의 한 행. 회차 하나 = 새 게임 한 번 = [1] 영구 계층의 그릇.
 *
 * endedAt 이 null 이면 진행 중이다. "진행 중" 을 별도 컬럼(boolean)으로 두지 않는 이유:
 * 끝난 시각을 알면 끝났는지도 아는데, 두 컬럼을 두면 둘이 어긋날 수 있다.
 */
public record Playthrough(Long id, Long userId, OffsetDateTime startedAt, OffsetDateTime endedAt) {
}
