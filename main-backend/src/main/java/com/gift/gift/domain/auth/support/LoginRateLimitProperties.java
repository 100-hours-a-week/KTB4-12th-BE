package com.gift.gift.domain.auth.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(
        prefix = "app.security.login-rate-limit"
)
public record LoginRateLimitProperties(
        @NotBlank String hmacSecretBase64,
        @DefaultValue("6") @Min(1) @Max(6) int ipRefillSeconds
) {

    @Override
    public String toString() {
        return "LoginRateLimitProperties[REDACTED]";
    }
}
