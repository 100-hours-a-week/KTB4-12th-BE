package com.gift.gift.domain.recommendation.support;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai-profile")
public record AiProfilingProperties(
        URI baseUrl,
        String serviceToken,
        Duration connectTimeout,
        Duration readTimeout,
        Duration quietPeriod,
        Duration maximumWindow,
        int batchSize
) {

    public AiProfilingProperties {
        Objects.requireNonNull(baseUrl, "AI Base URL은 필수입니다.");
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
                    "Batch 크기는 1 이상이어야 합니다."
            );
        }
    }

    @Override
    public String toString() {
        return "AiProfilingProperties[serviceToken=REDACTED]";
    }
}
