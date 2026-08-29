package com.sparta.springprepare.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 4xx/5xx 응답의 본문 형식. {code, message, detail?}
 *
 * M0에서 미리 형식을 하나로 두는 이유: 컨트롤러마다 다른 모양의 에러를 내기 시작하면
 * M6 "에러 응답 통일"에서 전부 걷어내야 한다. 지금 정해두면 M6에서는 누락 케이스만 채우면 된다 (D-004).
 *
 * code    — 클라가 분기할 때 쓰는 기계용 문자열 (NOT_FOUND, DUPLICATE, BAD_REQUEST, ...)
 * message — 사람이 읽는 한 줄
 * detail  — 있을 때만 (null이면 JSON에서 빠진다)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String detail) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
