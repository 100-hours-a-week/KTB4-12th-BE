package com.gift.gift.global.security;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security.cors")
public record CorsProperties(
        @NotEmpty List<@NotBlank String> allowedOrigins
) {
    public CorsProperties {
        if (allowedOrigins != null) {
            allowedOrigins = List.copyOf(allowedOrigins);
        }
    }
}
