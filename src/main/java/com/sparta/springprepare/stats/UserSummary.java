package com.sparta.springprepare.stats;

import java.time.OffsetDateTime;

/**
 * GET /users/{userId}/summary — 사용자 한 명의 플레이 요약 (PLAN M5).
 *
 * <p>{@code choices} 와 {@code playSeconds} 는 JOIN 이 아니라 **스칼라 서브쿼리**로 센다.
 * users ⋈ playthroughs ⋈ save_slots 로 이어 붙이면 행이 곱해지고(팬아웃), 그 상태의 SUM 은 틀린다.
 * COUNT 는 DISTINCT 로 고칠 수 있지만 SUM 은 못 고친다 — 값이 같다고 같은 행이 아니기 때문이다.
 * 자세한 이유는 {@code sql/stats/user_summary.sql} 주석에 있다.
 *
 * <p>{@code lastPlayedAt} 은 슬롯이 하나도 없으면 null 이다. 회차를 만들었지만 아직 저장한 적 없는 상태 —
 * 0 으로 채우지 않는 이유는 "없음" 과 "0" 이 다른 사실이기 때문이다.
 */
public record UserSummary(
        Long userId,
        String username,
        long playthroughs,
        long forks,
        long endedPlaythroughs,
        long saveSlots,
        long choices,
        long playSeconds,
        OffsetDateTime lastPlayedAt) {
}
