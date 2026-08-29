package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/**
 * PUT 요청에 동봉되는 선택 하나 (PLAN M3). 마지막 업로드 이후의 **증분**이다.
 *
 * seq 는 서버가 매기지 않는다 — 슬롯 안에서의 순번을 클라가 매긴다.
 * 오프라인에서 선택이 쌓이는 동안 서버는 존재하지 않으므로, 순서를 아는 쪽은 클라뿐이다.
 * 같은 seq 가 두 번 오면 `(save_slot_id, seq)` UNIQUE 가 막는다 — 그것이 M4 재전송 흡수의 재료다.
 *
 * chosenAt 은 **클라 시각**이다 (오프라인 플레이 반영). 서버 시각으로 덮지 않는다.
 * 서버가 받은 시각은 `received_at` 이 DB DEFAULT 로 따로 기록한다.
 * 형식은 ISO-8601 (D-009). 오프셋이 Z 든 +09:00 이든 서버가 UTC 로 정규화해 저장한다.
 */
public record ChoiceUpload(
        Integer seq,
        String episodeId,
        Integer optionIndex,
        OffsetDateTime chosenAt) {
}
