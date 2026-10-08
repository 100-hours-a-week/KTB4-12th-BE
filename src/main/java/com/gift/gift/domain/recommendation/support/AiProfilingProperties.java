package com.gift.gift.domain.recommendation.support;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ai-profile")
public record AiProfilingProperties(
        URI baseUrl,
        String serviceToken,
        Duration connectTimeout,
        Duration readTimeout,
        Duration quietPeriod,
        Duration maximumWindow,
        int batchSize,
        @DefaultValue("50") int recoveryBatchSize
) {

    public AiProfilingProperties {
        Objects.requireNonNull(
                baseUrl,
                "AI Base URL은 필수입니다."
        );
        Objects.requireNonNull(
                serviceToken,
                "AI 서비스 토큰은 필수입니다."
        );
        Objects.requireNonNull(
                connectTimeout,
                "연결 타임아웃은 필수입니다."
        );
        Objects.requireNonNull(
                readTimeout,
                "응답 타임아웃은 필수입니다."
        );
        Objects.requireNonNull(
                quietPeriod,
                "디바운스 시간은 필수입니다."
        );
        Objects.requireNonNull(
                maximumWindow,
                "최대 대기 시간은 필수입니다."
        );

        if (serviceToken.isBlank()) {
            throw new IllegalArgumentException(
                    "AI 서비스 토큰은 비어 있을 수 없습니다."
            );
        }

        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "연결 타임아웃은 0보다 커야 합니다."
            );
        }

        if (readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "응답 타임아웃은 0보다 커야 합니다."
            );
        }

        if (quietPeriod.isZero() || quietPeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "디바운스 시간은 0보다 커야 합니다."
            );
        }

        if (maximumWindow.isZero() || maximumWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "최대 대기 시간은 0보다 커야 합니다."
            );
        }

        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "일반 Batch 크기는 1 이상이어야 합니다."
            );
        }

        if (recoveryBatchSize < 0) {
            throw new IllegalArgumentException(
                    "복구 Batch 크기는 0 이상이어야 합니다."
            );
        }

        if ((long) batchSize + recoveryBatchSize > 200) {
            throw new IllegalArgumentException(
                    "일반 Batch와 복구 Batch의 합계는 200 이하여야 합니다."
            );
        }
    }

    @Override
    public String toString() {
        return "AiProfilingProperties[serviceToken=REDACTED]";
    }
}
