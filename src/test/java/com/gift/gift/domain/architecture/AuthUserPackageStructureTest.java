package com.gift.gift.domain.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class AuthUserPackageStructureTest {

    @Test
    @DisplayName("인증 영속성 모델과 지원 타입은 auth 도메인에 위치한다")
    void authPersistenceAndSupportTypesBelongToAuthDomain() {
        assertPresent("com.gift.gift.domain.auth.entity.UserSession");
        assertPresent("com.gift.gift.domain.auth.entity.LoginIpRateLimit");
        assertPresent("com.gift.gift.domain.auth.entity.LoginEmailFailureLimit");
        assertPresent("com.gift.gift.domain.auth.repository.UserSessionRepository");
        assertPresent("com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository");
        assertPresent("com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository");
        assertPresent("com.gift.gift.domain.auth.support.LoginResult");
        assertPresent("com.gift.gift.domain.auth.support.TokenRefreshResult");
        assertPresent("com.gift.gift.domain.auth.support.LoginRateLimitDecision");
        assertPresent("com.gift.gift.domain.auth.support.LoginRateLimitProperties");
        assertPresent("com.gift.gift.domain.auth.support.RateLimitIdentifierHasher");
    }

    @Test
    @DisplayName("로그인과 인증 세션 애플리케이션 타입은 auth 도메인에 위치한다")
    void authApplicationTypesBelongToAuthDomain() {
        assertPresent("com.gift.gift.domain.auth.controller.LoginController");
        assertPresent("com.gift.gift.domain.auth.controller.AuthSessionController");
        assertPresent("com.gift.gift.domain.auth.service.LoginService");
        assertPresent("com.gift.gift.domain.auth.service.LoginSessionService");
        assertPresent("com.gift.gift.domain.auth.service.LogoutService");
        assertPresent("com.gift.gift.domain.auth.service.TokenRefreshService");
        assertPresent("com.gift.gift.domain.auth.service.LoginRateLimiter");
        assertPresent("com.gift.gift.domain.auth.scheduler.AuthenticationDataCleanupScheduler");
        assertPresent("com.gift.gift.domain.auth.web.LoginIpRateLimitInterceptor");
    }

    private void assertPresent(String className) {
        assertThatCode(() -> Class.forName(className))
                .doesNotThrowAnyException();
    }
}
