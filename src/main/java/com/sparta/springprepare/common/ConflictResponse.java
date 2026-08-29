package com.sparta.springprepare.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 409 CONFLICT 응답 (PLAN M4).
 *
 * {@link ErrorResponse} 를 재사용하지 않고 따로 둔 이유: {@code ErrorResponse.detail} 은 {@code String} 이고,
 * 여기 실을 것은 <b>객체</b>(서버 슬롯 상태)다. detail 을 {@code Object} 로 넓히면 나머지 모든 에러 응답의
 * 계약이 느슨해진다 — 한 곳의 필요 때문에 전체를 헐겁게 만들지 않는다.
 *
 * <p>대가는 에러 응답 형식이 둘이 된다는 것이고, 그것을 M6 에서 통일한다. 지금은 {@code code}·{@code message}
 * 두 필드를 같은 이름으로 맞춰 두어 클라가 분기하는 방식은 바뀌지 않게 했다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConflictResponse(String code, String message, Object current) {
}
