package com.sparta.springprepare.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

/**
 * 예외 → HTTP 상태 번역을 한 곳에 모은다 (PLAN §2.5).
 *
 * 컨트롤러마다 try/catch 하지 않는 이유: 같은 예외가 항상 같은 상태 코드로 나가야 클라가 믿고 분기할 수 있다.
 * 번역 규칙이 흩어지면 "여기서는 409, 저기서는 500"이 된다.
 *
 * 핸들러 선택 규칙: 예외 클래스 계층에서 가장 가까운 @ExceptionHandler 가 이긴다.
 * DuplicateKeyException 은 DataIntegrityViolationException 의 하위이므로
 * 둘 다 있어도 중복 키는 409로, 나머지 제약 위반은 400으로 간다.
 *
 * M6 부터 맨 아래에 Exception 포괄 핸들러가 있어 <b>모든</b> 4xx/5xx 가 공통 계약
 * ({@code code}·{@code message} — D-004 M6 갱신)을 지킨다. 실습2 의 IllegalArgumentException 도
 * 이제 이 그물에 걸린다 — 상태 코드는 이전과 같은 500 이고 본문 모양만 통일됐다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    /**
     * 낙관적 동시성 충돌 (M4). 조건부 UPDATE 가 0행이었다 — DB 제약 위반이 아니라 **전제가 틀린 것**이다.
     *
     * DuplicateKeyException 과 같은 409 지만 code 가 다르다(`CONFLICT` vs `DUPLICATE`).
     * 예외 계층이 겹치지 않으므로 핸들러 선택에 모호함은 없다.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ConflictResponse> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictResponse("CONFLICT", e.getMessage(), e.current()));
    }

    /**
     * UNIQUE 제약 위반. M0에서는 users.username, 이후 (chapter_id, version), (save_slot_id, seq) 등.
     * DB가 막은 것을 앱이 409로 번역한다 — 앱은 "이미 있는지" 미리 SELECT 하지 않는다.
     * (SELECT 후 INSERT 는 두 요청이 동시에 오면 둘 다 통과한다. UNIQUE 만이 확실한 방어선이다.)
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE", "이미 존재하는 값입니다."));
    }

    /**
     * 그 밖의 제약 위반: NOT NULL, 컬럼 길이 초과, FK 없음 등.
     * 원인 메시지(e.getMostSpecificCause())는 DB 내부 정보(테이블·컬럼명)를 담고 있어 응답에 그대로 싣지 않고
     * 서버 로그로만 보낸다. 지금은 detail 에 클래스명만 준다 — 디버깅용이며 M6에서 정리.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("CONSTRAINT_VIOLATION", "요청이 DB 제약을 위반했습니다.",
                        e.getMostSpecificCause().getClass().getSimpleName()));
    }

    // ── M6-8: 여기부터가 "누락 케이스 채우기" ─────────────────────────────────

    /** 인증 실패 → 401 (M6-5). 인터셉터가 던진 것도 여기로 온다 — 에러 모양이 갈리지 않는다. */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", e.getMessage()));
    }

    /** 인가 실패 → 403 (M6-5·M6-6). 남의 회차, 남의 사용자 자원. */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", e.getMessage()));
    }

    /**
     * 깨진 JSON → 400. <b>M6-8 의 첫 항목</b> — M2 검증(F20)에서 이 예외가 핸들러를 우회해
     * Spring 기본 형식({timestamp,status,error,path})으로 새는 것을 실제로 목격했다.
     * 평범한 클라 버그(따옴표 누락, 잘린 본문)에서 매번 나오는 응답이라 code 로 분기할 수 있어야 한다.
     *
     * 파서의 원문 메시지(위치·토큰)는 detail 로도 싣지 않는다 — 클라가 할 일은 "본문을 고쳐 보내라"
     * 하나이고, 파서 내부 문자열에 의존하는 클라를 만들 이유가 없다. 서버 로그에는 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("본문 파싱 실패: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_JSON", "요청 본문이 올바른 JSON 이 아닙니다."));
    }

    /**
     * 서비스 층에서 파싱하다 터진 깨진 JSON → 400 (F44 잔손질, M7 착수 시 반영).
     *
     * 컨버터 층(@RequestBody 객체 바인딩)의 파싱 실패는 위의 HttpMessageNotReadableException 으로 잡히지만,
     * 콘텐츠 수입처럼 본문을 byte[] 로 받아 **서비스가 직접 readTree 하는 경로**는 JacksonException 이
     * 그대로 올라온다. M6 검증(M6-check §6)에서 이것이 포괄 핸들러의 500 으로 나가는 것이 실측됐다 —
     * 형식(code·message)은 지켰지만 원인은 클라의 깨진 본문이라 의미는 400 이 맞다.
     * code 를 컨버터 층과 같게 맞춰, 클라는 경로가 어디든 MALFORMED_JSON 하나로 분기한다.
     *
     * (writeValueAsString 쪽 실패도 같은 타입이지만, 방금 파싱해 든 JsonNode 를 되쓰는 경로라
     *  실제로 터질 자리가 없다 — 터진다면 400 이 아니라 버그이고, 그때는 로그가 말해 준다.)
     */
    @ExceptionHandler(JacksonException.class)
    public ResponseEntity<ErrorResponse> handleJackson(JacksonException e) {
        log.debug("본문 파싱 실패(서비스 층): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_JSON", "요청 본문이 올바른 JSON 이 아닙니다."));
    }

    /**
     * 스냅샷 상한 초과 → 413 (M8-A, D-022). 400 이 아닌 이유: 본문이 틀린 게 아니라 <b>큰</b> 것이고,
     * 클라가 할 일이 "고쳐 보내라"가 아니라 "줄여 보내라"다. 상태 코드가 그 차이를 말한다.
     */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(PayloadTooLargeException e) {
        // 413 의 이름이 RFC 9110 에서 "Content Too Large" 로 바뀌어 Spring 7 은 PAYLOAD_TOO_LARGE 를 deprecated 로 뒀다.
        // code 문자열은 D-022 대로 PAYLOAD_TOO_LARGE 를 유지한다 — 계약은 상태 번호와 code 이지 enum 이름이 아니다.
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ErrorResponse.of("PAYLOAD_TOO_LARGE", e.getMessage()));
    }

    /** 경로·쿼리 값의 타입 불일치(/users/abc 의 abc → long 실패) → 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("TYPE_MISMATCH",
                        "파라미터 형식이 올바르지 않습니다: " + e.getName()));
    }

    /**
     * 그 밖의 전부 → 원칙은 500. <b>단, Spring MVC 자신이 던지는 예외를 500 으로 뭉개면 안 된다.</b>
     *
     * 없는 경로(NoResourceFoundException→404), 틀린 메서드(HttpRequestMethodNotSupportedException→405)
     * 같은 예외는 Framework 7 에서 {@code org.springframework.web.ErrorResponse} 인터페이스를 구현하고
     * 자기 상태 코드를 들고 있다. 포괄 핸들러가 이들을 가로채므로(핸들러가 없으면 기본 처리기가 맡았을 것),
     * <b>상태 코드는 예외가 아는 값을 그대로 쓰고 본문만 우리 형식으로 바꾼다.</b>
     * 이걸 빼먹으면 "없는 URL 인데 500" 이 된다 — 형식을 통일하려다 의미를 부수는 자리다.
     *
     * (인터페이스는 @ExceptionHandler 값으로 못 쓴다 — Throwable 이 아니라서. 그래서 instanceof 로 가른다.)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatus status = HttpStatus.resolve(springError.getStatusCode().value());
            String code = status != null ? status.name() : "HTTP_" + springError.getStatusCode().value();
            return ResponseEntity.status(springError.getStatusCode())
                    .body(ErrorResponse.of(code, "요청을 처리할 수 없습니다."));
        }
        // 여기 도달했다는 것은 서버 버그다. 원인은 로그에 전부 남기고,
        // 응답에는 내부 정보(클래스명·스택)를 싣지 않는다 — CONSTRAINT_VIOLATION 의 detail 과 같은 선긋기.
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류입니다."));
    }
}
