package com.sparta.springprepare.auth;

import com.sparta.springprepare.common.BadRequestException;
import com.sparta.springprepare.common.UnauthorizedException;
import com.sparta.springprepare.user.User;
import com.sparta.springprepare.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * 로그인·로그아웃 (M6-4).
 *
 * <h3>토큰은 랜덤 문자열 + DB 행이다</h3>
 * JWT 가 아니다 (PLAN "하지 않는 것"). 서명 검증·클레임·리프레시의 복잡함 없이,
 * "행이 있으면 유효, 지우면 무효"라는 가장 단순한 모델이다. 상태가 DB 에 있으므로
 * 서버가 두 대가 되어도 그대로 동작하고, 로그아웃이 **즉시** 전 기기에서 유효하다 —
 * JWT 는 이 두 번째를 못 한다(만료 전 토큰을 서버가 무를 방법이 없다).
 */
@Service
public class AuthService {

    /**
     * SecureRandom 은 한 번 만들어 재사용한다. 매 로그인마다 new 하면
     * (특히 리눅스에서) 엔트로피 수집 때문에 느려질 수 있고, 재사용해도 안전하다 — 그러라고 만든 클래스다.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final int sessionHours;

    public AuthService(SessionRepository sessionRepository,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.auth.session-hours}") int sessionHours) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionHours = sessionHours;
    }

    /**
     * <h3>실패 이유를 구분해 주지 않는다</h3>
     * "아이디가 없다"와 "비밀번호가 틀렸다"가 같은 401, 같은 메시지다. 구분해 주면
     * 이 API 가 곧 **계정 존재 여부 조회기**가 된다 — 공격자는 아이디 목록을 만들 수 있다.
     * (M0~M5 의 다른 API 는 원인을 정확히 알려주는 쪽을 택해 왔다. 여기만 반대인 이유는
     * 로그인의 실패 원인이 **비밀**이기 때문이다. 숨기는 것에도 이유가 있어야 한다.)
     *
     * <p>참고: 저장된 비밀번호가 BCrypt 형식이 아니면(예: 해시 도입 전 평문 행)
     * matches 가 그냥 false 를 돌려준다 — 즉 옛 평문 계정은 로그인이 안 되는 것이지
     * 500 이 나는 것이 아니다. 재생성(M6-1b) 후에는 이 경우 자체가 없다.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new BadRequestException("username 은 비어 있을 수 없습니다.");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new BadRequestException("password 는 비어 있을 수 없습니다.");
        }

        User user = userRepository.findByUsername(request.username())
                .filter(found -> passwordEncoder.matches(request.password(), found.password()))
                .orElseThrow(() -> new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다."));

        String token = newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(sessionHours);
        sessionRepository.insert(token, user.id(), expiresAt);
        return new LoginResponse(token, user.id(), expiresAt);
    }

    /**
     * 로그아웃 = 행 삭제. 없는 토큰이어도 성공으로 친다 — 두 번 눌러도, 만료 후 눌러도
     * 결과는 같아야 한다(멱등). PlaythroughService.end 의 판단과 같은 결이다.
     */
    @Transactional
    public void logout(String token) {
        sessionRepository.deleteByToken(token);
    }

    /**
     * 32바이트 난수의 hex = 64자 (sessions.token CHAR(64)).
     * 256비트라 추측·충돌 걱정이 없고, PK 라 만에 하나 겹치면 INSERT 가 실패한다 — 조용히 덮이지 않는다.
     */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
