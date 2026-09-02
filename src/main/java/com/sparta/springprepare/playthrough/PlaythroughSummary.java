package com.sparta.springprepare.playthrough;

import java.time.OffsetDateTime;

/**
 * GET /users/{userId}/playthroughs 의 한 줄 (M2 + M8-A 확장, 핸드오프 R4).
 *
 * <p>이력 화면·복구 화면이 <b>스냅샷을 열지 않고</b> 회차 하나를 그리는 데 필요한 것 전부다.
 * 슬롯 1 의 값(chapterId 이하)은 LEFT JOIN 이라 슬롯이 없는 회차는 null 로 온다.
 *
 * <p>팬아웃 주의(M5 user_summary 의 교훈): 슬롯은 {@code slot_no = 1} 로 한정해 조인하고, 슬롯 수와
 * 즐겨찾기 수는 상관 서브쿼리로 센다. 셋을 그냥 조인하면 행이 곱해진다.
 *
 * @param forkedFrom        갈래면 출처. 새 게임이면 null. 안의 playthroughId 가 null 이면 부모가 아직 서버에 없다.
 * @param bookmarkCount     이 회차에서 찍은 즐겨찾기 수(삭제 제외). 클라 id 로 센다 — 항상 있는 값이라서.
 * @param lastSavedAt       슬롯 1 의 updated_at. 슬롯이 없으면 null.
 */
public record PlaythroughSummary(
        Long id,
        String clientPlaythroughId,
        ForkOrigin forkedFrom,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Integer slotCount,
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        Boolean chapterCompleted,
        Integer inheritedPlaySeconds,
        Integer ownPlaySeconds,
        Integer playSeconds,
        Integer bookmarkCount,
        OffsetDateTime lastSavedAt) {
}
