package com.sparta.springprepare.stats;

/**
 * GET /stats/chapters/{chapterId}/choices 의 한 줄 — 에피소드 한 옵션의 선택 현황 (PLAN M5).
 *
 * <p>{@code pickRate} 의 분모는 **그 에피소드에서의 총 선택 횟수**라서 에피소드마다 합이 100% 가 된다.
 *
 * <p>{@code choiceLabel} 은 색인이 아니라 **원본 JSON**에서 온다 — 색인 테이블에는 옵션 개수만 있고
 * 라벨은 없기 때문이다(라벨은 표시용이지 판정용이 아니므로 복제하지 않았다).
 * 그래서 null 일 수 있다: 이력에는 남아 있는데 콘텐츠에서 옵션이 사라진 경우다.
 * 그때도 <b>횟수는 나와야 하므로</b> 쿼리가 LEFT JOIN 이다.
 */
public record ChoiceRatioItem(
        String episodeId,
        String episodeTitle,
        Integer optionIndex,
        String choiceLabel,
        long picks,
        Double pickRate) {
}
