package com.sparta.springprepare.common;

import com.sparta.springprepare.auth.Session;
import com.sparta.springprepare.auth.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 토큰 인증 (M6-5). 등록 경로는 {@link WebConfig} — /playthroughs/**, /users, /users/**.
 *
 * <h3>인터셉터가 하는 일과 하지 않는 일</h3>
 * <ul>
 *   <li>한다 — <b>인증</b>: 토큰 → sessions 조회 → 만료 확인 → 요청 속성에 userId.</li>
 *   <li>한다 — /users/{id}/** 의 <b>본인 확인</b>: 경로의 id 만 보면 되므로 DB 를 더 볼 것이 없다.</li>
 *   <li>하지 않는다 — 회차 <b>소유 검증</b>: 회차의 user_id 를 알아야 해서 서비스가 한다 (M6-6).
 *       인터셉터가 회차를 조회하면 서비스가 같은 것을 또 조회한다 — 같은 지식을 두 곳에 두지 않는다.</li>
 * </ul>
 *
 * <h3>예외를 던지면 어떻게 되나</h3>
 * preHandle 은 핸들러 매핑 <b>뒤</b>에 돌므로, 여기서 던진 예외도 {@link GlobalExceptionHandler} 가
 * 받아 같은 형식({@code {code, message}})으로 나간다. 인터셉터라고 에러 모양이 달라지지 않는다.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 컨트롤러가 @RequestAttribute 로 꺼내 쓰는 속성 이름. */
    public static final String USER_ID_ATTRIBUTE = "authUserId";

    private static final String BEARER_PREFIX = "Bearer ";

    /** /users/{id} 또는 /users/{id}/... — 숫자 id 만. (숫자가 아니면 컨트롤러 바인딩이 400 을 낸다.) */
    private static final Pattern USERS_PATH = Pattern.compile("^/users/(\\d+)(/.*)?$");

    private final SessionRepository sessionRepository;

    public AuthInterceptor(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 회원가입만 공개다 — 가입하려는 사람은 아직 토큰이 없다. 패턴으로는 메서드를 못 가르므로 여기서 가른다.
        if ("POST".equals(request.getMethod()) && "/users".equals(request.getRequestURI())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Authorization: Bearer <토큰> 헤더가 필요합니다.");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        Session session = sessionRepository.findByToken(token)
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 토큰입니다. 다시 로그인하십시오."));
        // 만료 판정은 UTC 끼리 (D-009). expiresAt 은 드라이버가 UTC 로 읽어 온 값이다.
        if (!session.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new UnauthorizedException("만료된 토큰입니다. 다시 로그인하십시오.");
        }

        // /users/{id}/** 는 본인만. 경로가 이미 답을 들고 있으므로 서비스까지 갈 것 없이 여기서 끝낸다.
        Matcher usersPath = USERS_PATH.matcher(request.getRequestURI());
        if (usersPath.matches() && Long.parseLong(usersPath.group(1)) != session.userId()) {
            throw new ForbiddenException("다른 사용자의 자원입니다.");
        }

        request.setAttribute(USER_ID_ATTRIBUTE, session.userId());
        return true;
    }
}
