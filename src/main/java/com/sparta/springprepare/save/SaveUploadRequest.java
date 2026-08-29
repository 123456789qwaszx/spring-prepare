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
 * 이력은 쌓인다 — 그래서 한쪽은 덮어쓰기, 한쪽은 INSERT 다.
 * 둘 다 없어도 된다(null 이거나 빈 배열). 세이브만 올리는 요청이 정상 경로이기 때문이다.
 *
 * <h3>M4 에서 붙은 것 — baseRevision</h3>
 * <b>"내가 알고 있던 서버의 상태"</b>다. 클라는 직전 응답의 {@code revision} 을 보관했다가 그대로 되돌려 보낸다.
 * 서버는 이 값이 지금 DB 의 revision 과 같을 때만 쓴다 — 다르면 그 사이 누군가 끼어든 것이다.
 *
 * <p><b>필수다.</b> 없으면 400 이고, 이것으로 M2·M3 요청 형식과의 호환이 끊긴다 — 의도한 것이다.
 * 선택으로 두면 "안 보낸 요청은 무조건 덮어쓴다"가 되어 낙관적 동시성이 있으나 마나 해진다.
 *
 * <p>{@code long} 이 아니라 {@code Long} 인 이유는 {@code seq} 와 같다 — {@code long} 이면 Jackson 이
 * 안 보낸 요청에 0 을 채워 넣고, 서버는 그것을 "신규 슬롯 생성"으로 읽는다. null 이라야 "안 보냈다"를 안다.
 */
public record SaveUploadRequest(
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        JsonNode snapshot,
        Integer playSeconds,
        String deviceKey,
        Long baseRevision,
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
