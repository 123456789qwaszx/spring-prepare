package com.sparta.springprepare.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
 * 여기에 없는 예외(예: 실습2 MemoController 의 IllegalArgumentException)는 Spring 기본 처리(500)로 남는다.
 * 실습2 동작을 바꾸지 않기 위해 의도적으로 두었다. M6에서 전부 통일한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
