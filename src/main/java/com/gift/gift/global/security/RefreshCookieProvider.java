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
                "리프레시 토큰은 null일 수 없습니다."
        );

        return baseCookie(refreshToken)
                .maxAge(COOKIE_MAX_AGE)
                .build();
    }

    public ResponseCookie expire() {
        return baseCookie("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(
            String value
    ) {
        return ResponseCookie
                .from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/auth");
    }
}
