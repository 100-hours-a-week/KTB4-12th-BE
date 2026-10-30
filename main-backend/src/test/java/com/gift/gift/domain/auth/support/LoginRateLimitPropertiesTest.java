package com.gift.gift.domain.auth.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gift.gift.global.config.LoginRateLimitConfig;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LoginRateLimitConfig.class)
            .withPropertyValues("app.security.login-rate-limit.hmac-secret-base64="
                    + "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=");

    @Test
    @DisplayName("별도 설정이 없으면 기존 6초 보충 간격을 유지한다")
    void usesSixSecondsByDefault() {
        contextRunner.run(context -> assertThat(context.getBean(LoginRateLimitProperties.class)
                .ipRefillSeconds()).isEqualTo(6));
    }

    @Test
    @DisplayName("loadtest 프로필이 없으면 부하 테스트 환경변수는 적용하지 않는다")
    void ignoresLoadtestOverrideWithoutProfile() {
        contextRunner.withPropertyValues("LOADTEST_LOGIN_IP_REFILL_SECONDS=1")
                .run(context -> assertThat(context.getBean(LoginRateLimitProperties.class)
                        .ipRefillSeconds()).isEqualTo(6));
    }

    @Test
    @DisplayName("loadtest 프로필에서만 환경변수의 1초 보충 간격을 적용한다")
    void appliesOverrideWithLoadtestProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=loadtest",
                        "LOADTEST_LOGIN_IP_REFILL_SECONDS=1")
                .run(context -> assertThat(context.getBean(LoginRateLimitProperties.class)
                        .ipRefillSeconds()).isEqualTo(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 7})
    @DisplayName("부하 테스트 간격은 Redis TTL과 용량에 맞는 1~6초만 허용한다")
    void rejectsInvalidRefillInterval(int seconds) {
        contextRunner.withPropertyValues("spring.profiles.active=loadtest",
                        "LOADTEST_LOGIN_IP_REFILL_SECONDS=" + seconds)
                .run(context -> assertThat(context).hasFailed());
    }
}
