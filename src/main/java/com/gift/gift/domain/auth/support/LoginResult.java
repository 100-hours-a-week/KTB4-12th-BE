package com.gift.gift.domain.auth.support;

import java.util.Objects;

public record LoginResult(
        String accessToken,
        long expiresIn,
        String refreshToken,
        Long userId,
        String name,
        String email,
        boolean isFirstLogin
) {

    public LoginResult {
        Objects.requireNonNull(
                accessToken,
                "accessToken must not be null"
        );
        Objects.requireNonNull(
                refreshToken,
                "refreshToken must not be null"
        );
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        Objects.requireNonNull(
                name,
                "name must not be null"
        );
        Objects.requireNonNull(
                email,
                "email must not be null"
        );

        if (expiresIn <= 0) {
            throw new IllegalArgumentException(
                    "expiresIn must be positive"
            );
        }
    }

    @Override
    public String toString() {
        return "LoginResult[REDACTED]";
    }
}
