package com.sparta.springprepare.stats;

import java.time.OffsetDateTime;

/**
 * GET /stats/events 의 한 줄 — EventKey 하나의 도달 현황 (PLAN M5).
 *
 * <p>{@code reachRate} 의 분모는 **전체 회차**다 (종료 여부 무관). 쿼리 파일 첫머리에 그 정의가 적혀 있고,
 * 분모를 "종료된 회차" 로 바꾸면 같은 데이터에서 다른 답이 나온다 — 둘 다 맞는 숫자이고 물음이 다르다.
 * <b>비율을 응답에 실을 때는 분모가 무엇인지가 필드 이름만으로는 드러나지 않는다</b>는 점을 잊지 않는다.
 * (M6 에서 API 문서를 쓸 때 이 정의를 함께 적는다.)
 */
public record EventReachItem(
        String eventKey,
        long reachedPlaythroughs,
        long totalPlaythroughs,
        Double reachRate,
        OffsetDateTime firstOccurredAt,
        OffsetDateTime lastOccurredAt) {
}
