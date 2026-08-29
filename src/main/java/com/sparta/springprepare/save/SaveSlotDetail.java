package com.sparta.springprepare.save;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.OffsetDateTime;

/**
 * GET /playthroughs/{pid}/saves/{slotNo} — 요약에 스냅샷을 더한 것.
 *
 * snapshot 에 @JsonRawValue 를 붙인 이유:
 * 이 필드는 DB 에서 읽은 **이미 JSON 인 문자열**이다. 그냥 String 으로 두면 Jackson 이 JSON 문자열로
 * 감싸서 "{\"nodeName\":...}" 처럼 이스케이프된 채 나간다. @JsonRawValue 는 그것을 그대로 흘려보낸다.
 *
 * JsonNode 로 파싱해서 담을 수도 있지만, 그러면 읽고-쓰는 왕복이 한 번 더 생긴다.
 * 서버는 스냅샷을 열지 않는다는 원칙에도 이쪽이 더 가깝다 — 문자열이 들어와서 문자열로 나간다.
 * (애노테이션 패키지는 Jackson 3 에서도 com.fasterxml.jackson.annotation 그대로다.)
 */
public record SaveSlotDetail(
        Integer slotNo,
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        Long revision,
        Integer playSeconds,
        OffsetDateTime updatedAt,
        String device,
        @JsonRawValue String snapshot) {
}
