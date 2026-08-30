package com.sparta.springprepare.common;

/**
 * 인가 실패 → 403. "네가 누구인지는 알겠는데, 이건 네 것이 아니다."
 *
 * 401({@link UnauthorizedException})과 다르다 — 다시 로그인해도 소용없다는 것이 핵심이다.
 * 남의 회차·남의 세이브·남의 사용자 정보에 손대려 할 때가 전부 여기다.
 *
 * 404 가 아니라 403 인 것은 PLAN M6 완료 기준의 선택이다 ("남의 회차 → 403").
 * 403 은 "그 자원이 존재한다"는 사실을 흘리지만, 이 게임의 회차 id 는 비밀이 아니다 —
 * 숨겨서 얻는 것보다 클라가 원인을 정확히 아는 쪽의 값이 크다.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
