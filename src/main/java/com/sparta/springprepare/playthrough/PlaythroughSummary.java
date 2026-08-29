package com.sparta.springprepare.playthrough;

import java.time.LocalDateTime;

/** GET /users/{userId}/playthroughs 의 한 줄. slotCount 는 서브쿼리로 센다. */
public record PlaythroughSummary(
        Long id,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer slotCount) {
}