package com.sparta.springprepare.playthrough;

/**
 * 회차 생성 응답. 201(새로 만듦) 과 200(이미 있어서 그것) 모두 이 모양이다 — 상태 코드가 둘을 가른다 (D-019).
 * {@code clientPlaythroughId} 를 되돌려 주는 이유: 클라가 "내가 보낸 그 회차가 맞다"를 응답만으로 확인하게.
 */
public record PlaythroughCreatedResponse(Long playthroughId, String clientPlaythroughId) {
}
