package com.sparta.springprepare.save;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * PUT /playthroughs/{pid}/saves/{slotNo} 요청 본문 (PLAN M2 + M3).
 *
 * snapshot 이 String 이 아니라 JsonNode 인 이유:
 * String 으로 받으면 클라가 JSON 안에 JSON 을 문자열로 이스케이프해 넣어야 한다 ("{\"a\":1}").
 * JsonNode 로 받으면 클라는 그냥 객체를 쓴다. 서버는 그것을 **읽지 않고** writeValueAsString 으로 되돌려
 * JSON 컬럼에 넣는다 — 스냅샷 안의 어떤 키도 해석하지 않는다는 원칙(PLAN 1.4)은 그대로다.
 *
 * M1 의 콘텐츠 수입이 byte[] 를 받은 것과 다른 선택인데, 목적이 다르기 때문이다.
 * 콘텐츠는 checksum(=바이트의 함수)이 필요했고, 스냅샷은 그런 것이 없다.
 *
 * <h3>M3 에서 붙은 것</h3>
 * choices·events 는 **마지막 업로드 이후의 증분**이다. 스냅샷은 늘 최신 것 하나로 덮이지만
 * 이력은 쌓인다 — 그래서 한쪽은 upsert, 한쪽은 INSERT 다.
 * 둘 다 없어도 된다(null 이거나 빈 배열). 세이브만 올리는 요청이 정상 경로이기 때문이다.
 */
public record SaveUploadRequest(
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        JsonNode snapshot,
        Integer playSeconds,
        String deviceKey,
        List<ChoiceUpload> choices,
        List<EventUpload> events) {

    /** null 을 빈 목록으로 접는다 — 호출부마다 null 검사를 반복하지 않기 위해. */
    public List<ChoiceUpload> choicesOrEmpty() {
        return choices == null ? List.of() : choices;
    }

    public List<EventUpload> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }
}
