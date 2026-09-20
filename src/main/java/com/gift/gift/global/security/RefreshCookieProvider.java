package com.gift.gift.global.security;

import java.time.Duration;
import java.util.Objects;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookieProvider {

    public static final String COOKIE_NAME =
            "refreshToken";

    private static final Duration COOKIE_MAX_AGE =
            Duration.ofDays(14);

    public ResponseCookie create(String refreshToken) {
        Objects.requireNonNull(
                refreshToken,
                "refreshToken must not be null"
        );

        return ResponseCookie
                .from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/auth")
                .maxAge(COOKIE_MAX_AGE)
                .build();
    }
}
