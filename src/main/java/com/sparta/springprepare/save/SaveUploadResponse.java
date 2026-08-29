package com.sparta.springprepare.save;

import java.time.OffsetDateTime;

/**
 * PUT 응답 (PLAN M2 + M3 + M4).
 *
 * revision 은 클라가 보관했다가 다음 업로드에 {@code baseRevision} 으로 되돌려 보낼 값이다.
 * M2 에서는 그냥 늘어나는 숫자였고, M4 에서 낙관적 동시성의 키가 된다.
 *
 * <p>acceptedChoices·acceptedEvents 는 "몇 건을 **기록했는가**"다. M3 까지는 보낸 수와 항상 같았지만
 * (하나라도 틀리면 400 이고 전부 롤백되므로), M4 부터는 달라질 수 있고 그때 이 값이 실제 정보를 담는다:
 * <ul>
 *   <li>재전송({@code replayed=true}) → 0. 이미 있는 것을 다시 쓰지 않았다.</li>
 *   <li>{@code force=true} → 겹치지 않은 것만 센다. "보낸 수 &gt; 기록한 수" 가 정상이 된다.</li>
 * </ul>
 *
 * <p>updatedAt 은 OffsetDateTime 이라 {@code 2026-08-29T11:40:19Z} 로 나간다 (D-009).
 */
public record SaveUploadResponse(
        Long revision,
        OffsetDateTime updatedAt,
        int acceptedChoices,
        int acceptedEvents,
        boolean replayed) {

    /** 실제로 쓴 경우. */
    static SaveUploadResponse applied(SaveSlotState state, int acceptedChoices, int acceptedEvents) {
        return new SaveUploadResponse(state.revision(), state.updatedAt(),
                acceptedChoices, acceptedEvents, false);
    }

    /**
     * 재전송으로 판정한 경우 (D-010). <b>아무것도 쓰지 않았다</b> — revision 도 그대로다.
     *
     * 200 을 주는 이유: 클라 입장에서 이 요청의 목적은 이미 달성돼 있다. 409 를 주면 클라는
     * 있지도 않은 충돌을 사용자에게 물어보게 된다. 상태 코드는 "무슨 일이 있었나"가 아니라
     * "네 요청은 어떻게 됐나"를 말한다.
     */
    static SaveUploadResponse replayed(SaveSlotState state) {
        return new SaveUploadResponse(state.revision(), state.updatedAt(), 0, 0, true);
    }
}
