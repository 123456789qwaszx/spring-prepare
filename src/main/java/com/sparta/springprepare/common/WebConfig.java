package com.sparta.springprepare.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 설정 + 공용 빈 (M6-5).
 *
 * <h3>보호 경로 지도</h3>
 * <pre>
 *   /auth/**                     공개 (로그인하려는 사람은 토큰이 없다)
 *   POST /users                  공개 (회원가입) — AuthInterceptor 안에서 가른다
 *   /users/**, /playthroughs/**  Bearer 토큰. /users/{id}/** 는 본인만
 *   POST /content/**             X-Admin-Key. GET 은 공개 (클라 다운로드)
 *   /stats/**                    X-Admin-Key (D-013)
 *   /users/{id}/summary          토큰 경로에 속한다 — 유저용 요약은 본인 것만 (D-013)
 *   그 외 (/, /memos 등 실습1·2)  건드리지 않는다 — 실습2 동작을 바꾸지 않는다 (M0 부터의 원칙)
 * </pre>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminKeyInterceptor adminKeyInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, AdminKeyInterceptor adminKeyInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminKeyInterceptor = adminKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // "/users" 를 따로 적는 이유: "/users/**" 는 /users 자신을 포함하지만,
        // 포함 여부를 패턴 규칙의 기억에 맡기지 않고 눈에 보이게 적는다.
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/playthroughs/**", "/users", "/users/**");
        registry.addInterceptor(adminKeyInterceptor)
                .addPathPatterns("/content/**", "/stats/**");
    }

    /**
     * BCrypt (M6-3). strength 는 프로퍼티 — 테스트 프로필이 4 로 낮춘다 (C7).
     * 검증(matches)은 해시 문자열 안에 든 cost 를 따르므로, strength 가 달라도
     * 이미 만든 해시는 전부 검증된다 — seed 의 cost 10 해시도, 테스트의 cost 4 해시도.
     *
     * 인터페이스(PasswordEncoder)로 주입하는 이유: 쓰는 쪽(UserService, AuthService)이
     * 알아야 하는 것은 encode/matches 뿐이고, BCrypt 라는 선택은 여기 한 곳에만 적는다.
     */
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${app.auth.bcrypt-strength}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
