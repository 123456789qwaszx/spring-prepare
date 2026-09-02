package com.sparta.springprepare.common;

/** 스냅샷이 상한을 넘었다 → 413 (D-022). 세이브와 즐겨찾기가 같은 상한을 쓴다. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
