package com.gift.gift.global.config;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gift.gift.domain.user.support.LoginRateLimitProperties;

@Configuration
@EnableConfigurationProperties(
        LoginRateLimitProperties.class
)
public class LoginRateLimitConfig {

    private static final int MINIMUM_KEY_BYTES = 32;

    @Bean
    @Qualifier("loginRateLimitHmacKey")
    public SecretKey loginRateLimitHmacKey(
            LoginRateLimitProperties properties
    ) {
        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(
                    properties.hmacSecretBase64()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Login rate limit HMAC key must be Base64",
                    exception
            );
        }

        if (keyBytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Login rate limit HMAC key must contain at least 32 bytes"
            );
        }

        return new SecretKeySpec(
                keyBytes,
                "HmacSHA256"
        );
    }
}
