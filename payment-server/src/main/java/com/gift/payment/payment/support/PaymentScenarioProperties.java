package com.gift.payment.payment.support;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.test-scenario")
public record PaymentScenarioProperties(Duration delay) {

    public PaymentScenarioProperties {
        Objects.requireNonNull(delay, "결제 테스트 시나리오 지연 시간은 필수입니다.");

        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("결제 테스트 시나리오 지연 시간은 양수여야 합니다.");
        }
    }
}
