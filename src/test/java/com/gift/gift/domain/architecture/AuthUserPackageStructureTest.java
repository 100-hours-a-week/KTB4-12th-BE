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

    private void assertPresent(String className) {
        assertThatCode(() -> Class.forName(className))
                .doesNotThrowAnyException();
    }
}
