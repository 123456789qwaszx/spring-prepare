package com.sparta.springprepare.save;

import tools.jackson.databind.JsonNode;

/**
 * PUT /playthroughs/{pid}/saves/{slotNo} 요청 본문.
 *
 * snapshot 이 String 이 아니라 JsonNode 인 이유:
 * String 으로 받으면 클라가 JSON 안에 JSON 을 문자열로 이스케이프해 넣어야 한다 ("{\"a\":1}").
 * JsonNode 로 받으면 클라는 그냥 객체를 쓴다. 서버는 그것을 **읽지 않고** writeValueAsString 으로 되돌려
 * JSON 컬럼에 넣는다 — 스냅샷 안의 어떤 키도 해석하지 않는다는 원칙(PLAN 1.4)은 그대로다.
 *
 * M3 에서 choices, events 배열이 여기에 붙는다.
 */
public record SaveUploadRequest(
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        JsonNode snapshot,
        Integer playSeconds,
        String deviceKey) {
}
