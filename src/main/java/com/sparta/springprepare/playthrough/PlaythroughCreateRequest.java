package com.sparta.springprepare.playthrough;

/**
 * POST /users/{userId}/playthroughs 본문 (M8-A, D-019).
 *
 * <p>{@code clientPlaythroughId} 는 클라가 회차 파일 이름으로 쓰는 로컬 guid(32 hex)다. 이것이 멱등 키다 —
 * 같은 값으로 다시 오면 서버는 있던 회차를 돌려준다. <b>필수</b>다. 선택으로 두면 "안 보낸 요청은 멱등이
 * 아니다"가 되어, M4 가 baseRevision 을 선택으로 뒀을 때 생겼을 있으나 마나와 같아진다.
 *
 * <p>M7 까지의 본문 없는 POST 는 이제 400 이다 — 의도한 호환 단절(M6 이 토큰으로 끊은 것과 같은 종류).
 *
 * @param forkedFrom 갈라진 회차면 출처. 새 게임이면 null.
 */
public record PlaythroughCreateRequest(String clientPlaythroughId, ForkOrigin forkedFrom) {
}
