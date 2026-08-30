package com.sparta.springprepare.common;

/**
 * 인증 실패 → 401. "네가 누구인지 모르겠다."
 *
 * 403({@link ForbiddenException})과 다르다 — 401 은 신원의 문제고 403 은 권한의 문제다.
 * 토큰이 없거나, 형식이 틀렸거나, 만료됐거나, 로그인 정보가 틀렸을 때가 전부 여기다.
 * 클라의 대응은 하나다: 로그인 화면으로 보낸다.
 *
 * 로그인 실패에서 "아이디가 없다"와 "비밀번호가 틀렸다"를 구분해 주지 않는 이유:
 * 구분해 주면 응답이 곧 **계정 존재 여부 조회 API** 가 된다. 메시지를 하나로 합쳐
 * 밖에서는 어느 쪽인지 알 수 없게 한다 (AuthService 참조).
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
