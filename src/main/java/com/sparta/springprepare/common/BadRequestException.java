package com.sparta.springprepare.common;

/**
 * 요청 내용이 규칙에 어긋난다 → 400. (필수 값 누락, 범위 밖 등 — 앱이 판단하는 규칙)
 *
 * DB 제약 위반(NOT NULL, 길이, FK)도 400이지만 그것은 DataIntegrityViolationException 으로
 * 따로 잡는다. "앱이 먼저 거른 것"과 "DB가 마지막에 막은 것"을 code 로 구분해 두면
 * 어느 층이 일했는지 응답만 보고 알 수 있다 (M0의 학습 목표).
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
