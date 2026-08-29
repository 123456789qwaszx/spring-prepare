package com.sparta.springprepare.save;

import java.time.LocalDateTime;

/**
 * GET /playthroughs/{pid}/saves 의 한 줄. **스냅샷이 없다.**
 *
 * 목록은 세이브 선택 화면용이고 스냅샷은 수십 KB가 될 수 있다.
 * DTO 에서 빼는 것과 SELECT 에서 빼는 것은 다르다 — 후자여야 DB→앱 사이에도 흐르지 않는다.
 * 그래서 레포지토리의 목록 쿼리는 snapshot 컬럼을 아예 읽지 않는다.
 *
 * device 는 기기의 device_key 다. save_slots.device_id 가 NULL 허용이라 LEFT JOIN 이고, 없으면 null 이다.
 */
public record SaveSlotSummary(
        Integer slotNo,
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        Long revision,
        Integer playSeconds,
        LocalDateTime updatedAt,
        String device) {
}
