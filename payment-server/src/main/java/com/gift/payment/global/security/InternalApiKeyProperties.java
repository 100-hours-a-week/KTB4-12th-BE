package com.gift.payment.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.security.internal-api")
public record InternalApiKeyProperties(String key) {

    public InternalApiKeyProperties {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Payment Server API Key는 비어 있을 수 없습니다.");
        }
    }
}
