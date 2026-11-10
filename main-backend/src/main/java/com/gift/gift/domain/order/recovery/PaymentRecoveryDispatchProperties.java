package com.gift.gift.domain.order.recovery;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.payment.recovery.dispatch")
public record PaymentRecoveryDispatchProperties(
        @DefaultValue("100") @Min(1) @Max(100) int batchSize,
        @DefaultValue("60") @Min(6) @Max(3600) int leaseSeconds,
        @DefaultValue("5000") @Min(1) @Max(60000) int pollIntervalMs
) {
    @AssertTrue(message = "복구 작업 임대는 Publisher Confirm 대기 시간보다 길어야 합니다.")
    public boolean isLeaseValid() {
        return leaseSeconds > 5;
    }
}
