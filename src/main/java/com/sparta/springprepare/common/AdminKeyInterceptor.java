package com.sparta.springprepare.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 관리자 키 (M6-7, D-013). 등록 경로는 {@link WebConfig} — /content/**, /stats/**.
 *
 * 담당이 경로마다 다르다:
 * <ul>
 *   <li>/content/** — <b>POST 만.</b> GET 은 클라(Unity)가 콘텐츠를 내려받는 공개 경로다 (M6 계획서 §6 C5).</li>
 *   <li>/stats/** — <b>전 메서드.</b> 집계는 관리자용이다 (D-013). 유저용 요약(/users/{id}/summary)은
 *       이 인터셉터가 아니라 토큰 경로에 있어 자연히 본인 전용이 된다.</li>
 * </ul>
 *
 * 토큰(sessions)이 아니라 별도 헤더인 이유: 관리자는 게임 계정이 아니다. 콘텐츠를 올리는 사람이
 * 게임에 가입해야 한다는 요구는 이상하고, 반대로 아무 가입자나 콘텐츠를 올릴 수 있어도 안 된다.
 */
@Component
public class AdminKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Key";

    private final byte[] adminKey;

    /** 값이 프로퍼티에 없으면 기동이 실패한다 — 조용히 무인증으로 뜨는 것보다 낫다 (application.properties 주석). */
    public AdminKeyInterceptor(@Value("${app.admin-key}") String adminKey) {
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getRequestURI().startsWith("/content") && !"POST".equals(request.getMethod())) {
            return true;
        }

        String given = request.getHeader(HEADER);
        // equals 가 아니라 MessageDigest.isEqual: 문자열 equals 는 첫 불일치에서 멈춰
        // 응답 시간이 "몇 글자 맞았는지"를 흘린다(타이밍 공격). isEqual 은 길이가 같으면 끝까지 비교한다.
        // 이 규모에서 실익은 작지만, 비밀 비교의 관용구라 배워 두는 값이 있다.
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), adminKey)) {
            throw new UnauthorizedException("관리자 키가 필요합니다 (" + HEADER + " 헤더).");
        }
        return true;
    }
}
