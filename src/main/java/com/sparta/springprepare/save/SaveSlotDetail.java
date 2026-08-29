package com.sparta.springprepare.save;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.LocalDateTime;

/**
 * GET /playthroughs/{pid}/saves/{slotNo}
 *
 * snapshot 에 @JsonRawValue 를 붙인 이유:
 * 이 필드는 DB 에서 읽은 **이미 JSON 인 문자열**이다.
 * 그냥 String 으로 두면 Jackson 이 JSON 문자열로 감싸서
 * "{\"nodeName\":...}" 처럼 이스케이프된 채 나간다. @JsonRawValue 는 그것을 그대로 흘려보낸다.
 */
public record SaveSlotDetail(
        Integer slotNo,
        String chapterId,
        Integer chapterVersion,
        String currentEpisodeId,
        Long revision,
        Integer playSeconds,
        LocalDateTime updatedAt,
        String device,
        @JsonRawValue String snapshot) {
}