package com.gift.gift.global.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @DefaultValue("10") @Min(1) @Max(1000) int notificationBatchSize,
        @DefaultValue("10") @Min(1) @Max(1000) int searchBatchSize,
        @DefaultValue("60") @Min(1) @Max(3600) int leaseSeconds,
        @DefaultValue("5000") @Min(1) @Max(60000) int confirmTimeoutMs,
        @DefaultValue("10") @Min(1) @Max(100) int maxFailures,
        @DefaultValue("2") @Min(1) @Max(3600) int retryBaseSeconds,
        @DefaultValue("300") @Min(1) @Max(86400) int retryMaxSeconds
) {
    @AssertTrue(message = "lease는 confirm timeout보다 길고 최대 백오프는 기본 백오프 이상이어야 합니다.")
    public boolean isTimingValid() {
        return leaseSeconds * 1000L > confirmTimeoutMs && retryMaxSeconds >= retryBaseSeconds;
    }
}
