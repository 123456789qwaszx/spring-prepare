package com.sparta.springprepare.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 로직 단위 테스트 (PLAN §2.6). Spring 컨텍스트도 DB도 띄우지 않아 즉시 끝난다.
 *
 * 기대값은 직접 계산한 것이 아니라 SHA-256 의 **공표된 표준 벡터**다.
 * 구현이 만든 값을 그대로 기대값으로 박으면 "구현이 자기 자신과 같다"만 증명하게 된다.
 */
class ChecksumTest {

    @Test
    void 알려진_표준_벡터와_일치한다() {
        // FIPS 180-2 부록 예시: SHA-256("abc")
        assertThat(Checksum.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void 빈_입력도_정해진_값이_있다() {
        assertThat(Checksum.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void 줄바꿈이_다르면_다른_값이다() {
        // 이것이 "재수입 판정은 바이트 기준"의 실제 의미다.
        // 같은 JSON 이라도 CRLF ↔ LF 가 바뀌면 서버는 다른 파일로 보고 version 을 올린다.
        String lf = "{\n  \"a\": 1\n}";
        String crlf = "{\r\n  \"a\": 1\r\n}";

        assertThat(Checksum.sha256Hex(lf.getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(Checksum.sha256Hex(crlf.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 같은_바이트는_항상_같은_값이다() {
        byte[] bytes = "무결성".getBytes(StandardCharsets.UTF_8);
        assertThat(Checksum.sha256Hex(bytes)).isEqualTo(Checksum.sha256Hex(bytes));
    }

    @Test
    void 결과는_소문자_16진수_64자다() {
        assertThat(Checksum.sha256Hex("x".getBytes(StandardCharsets.UTF_8)))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}
