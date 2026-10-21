package com.gift.payment.payment.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentScenarioResolverTest {

    private final Environment environment = mock(Environment.class);

    private final PaymentScenarioResolver resolver =
            new PaymentScenarioResolver(environment);

    @Test
    @DisplayName("시나리오 헤더가 없으면 정상 승인을 사용한다")
    void resolve_returnsApprove_whenHeaderIsMissing() {
        PaymentScenario result = resolver.resolve(null);

        assertThat(result).isEqualTo(PaymentScenario.APPROVE);
    }

    @Test
    @DisplayName("개발 환경에서는 결제 테스트 시나리오를 허용한다")
    void resolve_returnsScenario_inDevelopment() {
        when(environment.acceptsProfiles(
                any(Profiles.class)
        )).thenReturn(true);

        PaymentScenario result = resolver.resolve("timeout_after_approval");

        assertThat(result).isEqualTo(PaymentScenario.TIMEOUT_AFTER_APPROVAL);
    }

    @Test
    @DisplayName("운영 환경에서는 결제 테스트 헤더를 거절한다")
    void resolve_rejectsScenario_inProduction() {
        when(environment.acceptsProfiles(
                any(Profiles.class)
        )).thenReturn(false);

        assertInvalidRequest(
                () -> resolver.resolve("APPROVE")
        );
    }

    @Test
    @DisplayName("허용되지 않은 테스트 시나리오는 거절한다")
    void resolve_rejectsUnknownScenario() {
        when(environment.acceptsProfiles(
                any(Profiles.class)
        )).thenReturn(true);

        assertInvalidRequest(
                () -> resolver.resolve("UNKNOWN")
        );
    }

    private void assertInvalidRequest(
            org.assertj.core.api.ThrowableAssert
                    .ThrowingCallable callable
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode()
                        ).isEqualTo(ErrorCode.INVALID_REQUEST)
                );
    }
}
