package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/**
 * PUT 응답 (PLAN M2 + M3).
 *
 * revision 이 클라에게 나가는 이유는 M4 때문이다. 클라는 이 값을 보관했다가 다음 업로드에
 * baseRevision 으로 되돌려 보내고, 서버는 그것으로 충돌을 감지한다. 지금은 그냥 늘어나는 숫자지만
 * M4 에서 낙관적 동시성의 키가 된다.
 *
 * acceptedChoices·acceptedEvents 는 "몇 건을 기록했는가"다. 지금은 보낸 수와 항상 같다 —
 * 하나라도 틀리면 400 이고 전부 롤백되기 때문이다. M4 에서 재전송을 흡수하기 시작하면
 * "보낸 수 > 기록한 수" 가 정상이 되고, 그때 이 값이 실제 정보를 담는다.
 *
 * updatedAt 은 OffsetDateTime 이라 `2026-08-29T11:40:19Z` 로 나간다 (D-009).
 * 클라가 "이게 무슨 시간대지?" 를 짐작할 필요가 없다.
 */
public record SaveUploadResponse(
        Long revision,
        OffsetDateTime updatedAt,
        int acceptedChoices,
        int acceptedEvents) {

    static SaveUploadResponse of(SaveSlotState state, int acceptedChoices, int acceptedEvents) {
        return new SaveUploadResponse(state.revision(), state.updatedAt(), acceptedChoices, acceptedEvents);
    }
}
