package com.gift.gift.domain.product.search;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.product-search.sync")
public record ProductSearchSyncProperties(
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize,
        @DefaultValue("60") @Min(1) @Max(3600) int leaseSeconds,
        @DefaultValue("10") @Min(1) @Max(100) int maxFailures,
        @DefaultValue("2") @Min(1) @Max(3600) int retryBaseSeconds,
        @DefaultValue("300") @Min(1) @Max(86400) int retryMaxSeconds,
        @DefaultValue("5000") @Min(100) @Max(3600000) long pollIntervalMs
) {
    @AssertTrue(message = "최대 재시도 간격은 기본 간격 이상이어야 합니다.")
    public boolean isRetryRangeValid() {
        return retryMaxSeconds >= retryBaseSeconds;
    }
}
