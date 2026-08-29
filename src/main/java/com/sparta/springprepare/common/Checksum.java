package com.sparta.springprepare.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 업로드된 원본 바이트의 SHA-256(hex 소문자). 콘텐츠 재수입 판정 키다 (PLAN M1).
 *
 * "바이트의" 함수라는 점이 중요하다. 같은 JSON이라도 들여쓰기나 줄바꿈(CRLF/LF)이 다르면 다른 체크섬이 나오고,
 * 서버는 그것을 "다른 파일"로 본다. 이는 버그가 아니라 정의다 — 서버는 JSON의 의미를 해석하지 않으므로
 * "같은 파일"의 유일한 근거가 바이트뿐이다. (VnTool 이 내보내기 형식을 바꾸면 version 이 하나 오른다.)
 *
 * 순수 함수라 단위 테스트 대상이다 (PLAN §2.6: 단위 테스트는 순수 로직만).
 */
public final class Checksum {

    private Checksum() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // HexFormat 은 Java 17 표준. 직접 String.format("%02x") 루프를 돌리지 않는다.
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 반드시 지원한다(JLS 보장). 여기 오면 JVM 이 망가진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
