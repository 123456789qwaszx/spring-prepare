package com.sparta.springprepare.playthrough;

import java.time.LocalDateTime;

/**
 * POST /playthroughs/{id}/end 응답.
 * PLAN 은 본문을 정하지 않았지만, 끝난 시각을 돌려주면 클라가 다시 조회하지 않아도 된다.
 */
public record PlaythroughEndResponse(Long id, LocalDateTime endedAt) {
}