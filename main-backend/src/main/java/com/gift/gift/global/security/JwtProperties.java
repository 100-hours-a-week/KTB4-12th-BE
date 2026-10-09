package com.gift.gift.global.security;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank String secretBase64,
        @NotBlank String issuer
) {
    @Override
    public String toString() {
        return "JwtProperties[REDACTED]";
    }
}
