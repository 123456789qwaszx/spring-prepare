package com.sparta.springprepare.common;

/**
 * 낙관적 동시성 충돌 → 409 (PLAN M4).
 *
 * <h3>DuplicateKeyException 과 다르다</h3>
 * 둘 다 409 지만 뜻이 다르다.
 * <ul>
 *   <li>{@code DUPLICATE} — "이미 있는 값이다". DB 의 UNIQUE 가 막았다. 같은 username, 같은 seq.</li>
 *   <li>{@code CONFLICT} — "네가 알던 상태가 낡았다". 제약 위반이 아니라 <b>조건부 UPDATE 가 0행</b>이다.</li>
 * </ul>
 * 클라의 대응도 다르다. 앞은 요청을 고쳐야 하고, 뒤는 <b>사용자에게 물어야 한다</b> ("덮어쓸까요?").
 *
 * <h3>왜 현재 상태를 함께 싣나</h3>
 * 409 만 던지면 클라는 다시 GET 해야 하고, 그 사이 또 바뀔 수 있다. 충돌을 감지한 그 순간의 상태를
 * 응답에 실어 주면 클라는 한 번의 왕복으로 "무엇과 부딪혔는지"를 안다 — M8 충돌 UI 가 보여줄 것이 이것이다.
 *
 * <p>{@code current} 가 {@code Object} 인 이유: {@code common} 이 {@code save} 를 알면 의존이 거꾸로 선다.
 * 던지는 쪽이 형식을 정하고 핸들러는 그대로 직렬화만 한다. 헐렁한 타입이지만 방향을 지키는 값이 더 크다.
 * (에러 응답 형식 통일은 M6 에서 다시 본다.)
 */
public class ConflictException extends RuntimeException {

    private final transient Object current;

    public ConflictException(String message, Object current) {
        super(message);
        this.current = current;
    }

    public Object current() {
        return current;
    }
}
