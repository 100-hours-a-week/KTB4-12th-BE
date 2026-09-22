package com.gift.gift.domain.auth.support;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(
        prefix = "app.security.login-rate-limit"
)
public record LoginRateLimitProperties(
        @NotBlank String hmacSecretBase64
) {

    @Override
    public String toString() {
        return "LoginRateLimitProperties[REDACTED]";
    }
}
