package com.sparta.springprepare.common;

/**
 * 요청한 자원이 없다 → 404.
 *
 * RuntimeException을 상속하는 이유: 서비스 메서드 시그니처에 throws를 붙이지 않기 위해서이기도 하지만,
 * 더 중요한 것은 @Transactional 의 기본 롤백 규칙이 "RuntimeException(unchecked)만 롤백"이라는 점이다.
 * 이 프로젝트의 예외는 전부 unchecked로 통일한다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
