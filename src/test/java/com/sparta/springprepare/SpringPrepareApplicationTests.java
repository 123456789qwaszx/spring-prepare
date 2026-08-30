package com.sparta.springprepare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * D-014: test 프로필로 고정한다. 프로필 없이 두면 application.properties 의
 * spring.profiles.active=local 이 그대로 적용돼 — 이 "테스트"가 개발 DB(game)에
 * 붙는다. M6 부터는 Flyway 까지 물려 있어, 컨텍스트만 띄워도 개발 DB 에
 * 마이그레이션이 도는 셈이었다. 테스트는 전부 game_test 만 본다 (D-002).
 */
@SpringBootTest
@ActiveProfiles("test")
class SpringPrepareApplicationTests {

    @Test
    void contextLoads() {
    }

}
