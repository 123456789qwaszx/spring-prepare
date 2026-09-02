package com.sparta.springprepare.bookmark;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * PUT /users/{userId}/bookmarks/{clientBookmarkId} 본문 (M8-A, D-021).
 *
 * <p>즐겨찾기 = 이력 위의 한 점의 <b>사본</b>. 스스로 완결돼 있어 출처 회차가 없어도 산다. 그래서 회차가 아니라
 * 사용자 밑의 자원이고, 경로의 clientBookmarkId(클라 guid)가 멱등 키다 — 같은 id 로 다시 오면 덮는다.
 *
 * <p>snapshot 은 세이브와 같은 규칙이다: JsonNode 로 받아 되쓰고 <b>열지 않는다</b>. 서버가 보는 것은 크기뿐(D-022).
 *
 * @param playthroughClientId 출처 회차의 클라 id. 없을 수 있다(즐겨찾기만 남기고 회차 파일을 지운 경우).
 *                            서버 id 링크는 있으면 지금, 없으면 그 회차가 오는 순간 채운다 (D-020 과 같은 결).
 * @param createdAt           클라가 찍은 시각(UTC). null 이면 서버 시각.
 */
public record BookmarkUpsertRequest(
        String label,
        String preview,
        String chapterId,
        Integer chapterVersion,
        String playthroughClientId,
        Integer sceneIndex,
        OffsetDateTime createdAt,
        JsonNode snapshot) {
}
