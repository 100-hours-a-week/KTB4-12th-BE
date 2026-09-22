package com.gift.gift.domain.auth.support;

import java.util.Objects;

public record TokenRefreshResult(
        String accessToken,
        long expiresIn,
        String refreshToken
) {
    public TokenRefreshResult {
        Objects.requireNonNull(
                accessToken,
                "accessToken must not be null"
        );
        Objects.requireNonNull(
                refreshToken,
                "refreshToken must not be null"
        );

        if (expiresIn <= 0) {
            throw new IllegalArgumentException(
                    "expiresIn must be positive"
            );
        }
    }

    @Override
    public String toString() {
        return "TokenRefreshResult[REDACTED]";
    }
}
