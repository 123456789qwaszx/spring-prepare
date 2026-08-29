package com.sparta.springprepare.playthrough;

import java.time.LocalDateTime;

/**
 * playthroughs 의 한 행. 영구 계층이 들어갈 자리.
 * endedAt 이 null 이면 진행 중.
 */
public record Playthrough(Long id, Long userId, LocalDateTime startedAt, LocalDateTime endedAt) {
}