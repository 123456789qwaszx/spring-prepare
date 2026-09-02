package com.sparta.springprepare.stats;

/**
 * GET /stats/chapters/{chapterId}/overview 의 답 (M9-1, D-025). 컬럼 별칭과 이름이 같다 — {@code sql/stats/chapter_overview.sql}.
 *
 * @param completed      "끝낸 회차" = 슬롯 1 의 chapter_completed. ended_at 이 아니다 (D-025).
 * @param completionRate 회차가 0 이면 0.0 — 화면이 "0 / 0 = ?" 를 그리지 않게 SQL 이 접는다.
 */
public record ChapterOverview(
        String chapterId,
        int version,
        long playthroughs,
        long completed,
        Double completionRate,
        long forks,
        long bookmarks) {
}
