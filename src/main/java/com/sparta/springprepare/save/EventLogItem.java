package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/**
 * GET /playthroughs/{pid}/events 의 한 줄. [1] 영구 계층의 실체다.
 *
 * chapterId·chapterVersion·chapterDisplayName 은 JOIN 으로 붙인다. event_log 자신은
 * `chapter_content_id` 라는 숫자만 들고 있고, 그것이 어느 챕터의 몇 번째 버전인지는 chapter_contents 가 안다.
 * "색인을 두는 목적은 판정이 아니라 JOIN" 이라는 schema.sql 주석이 여기서 실현된다.
 */
public record EventLogItem(
        String eventKey,
        String chapterId,
        Integer chapterVersion,
        String chapterDisplayName,
        String episodeId,
        OffsetDateTime occurredAt) {
}
