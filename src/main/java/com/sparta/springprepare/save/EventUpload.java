package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/**
 * PUT 요청에 동봉되는 이벤트 하나 — "EventKey 가 붙은 에피소드를 다 봤다" (PLAN M3).
 *
 * <b>eventKey 를 클라가 보내지 않는다.</b> 서버가 `chapter_episodes.event_key` 에서 찾는다.
 * 클라가 보내면 콘텐츠의 EventKey 와 다른 값을 올릴 수 있고, 그러면 [1] 영구 계층이 콘텐츠와 갈린다.
 * "두 곳에 있으면 갈린다" 는 원칙이 여기에도 적용된다 — EventKey 의 주인은 콘텐츠 파일이다.
 */
public record EventUpload(
        String episodeId,
        OffsetDateTime occurredAt) {
}
