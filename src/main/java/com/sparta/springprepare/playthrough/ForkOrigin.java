package com.sparta.springprepare.playthrough;

/**
 * 갈라진 출처 (M8-A, D-020). 요청과 응답이 같은 모양을 쓴다.
 *
 * <p>요청에서는 {@code clientPlaythroughId}·{@code sceneIndex} 만 의미가 있다. {@code playthroughId}(서버 id)는
 * 받아도 <b>믿지 않는다</b> — 소유 검증 없이 남의 회차 id 를 부모로 적을 수 있기 때문이다. 서버는 같은 사용자
 * 안에서 클라 id 로 부모를 찾고, 있으면 그때 서버 id 를 채운다. 없으면 비워 두고 부모가 오는 순간 되채운다.
 *
 * <p>응답에서는 셋 다 채워질 수 있다. {@code playthroughId} 가 null 이면 "부모가 아직 서버에 없다"이다.
 */
public record ForkOrigin(Long playthroughId, String clientPlaythroughId, Integer sceneIndex) {
}
