package com.gift.gift.global.config;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gift.gift.domain.auth.support.LoginRateLimitProperties;

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
                    "로그인 요청 제한 HMAC 키는 Base64 형식이어야 합니다.",
                    exception
            );
        }

        if (keyBytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "로그인 요청 제한 HMAC 키는 32바이트 이상이어야 합니다."
            );
        }

        return new SecretKeySpec(
                keyBytes,
                "HmacSHA256"
        );
    }
}
