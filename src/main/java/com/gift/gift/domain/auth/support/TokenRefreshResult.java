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
                "액세스 토큰은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                refreshToken,
                "리프레시 토큰은 null일 수 없습니다."
        );

        if (expiresIn <= 0) {
            throw new IllegalArgumentException(
                    "토큰 유효 시간은 1초 이상이어야 합니다."
            );
        }
    }

    @Override
    public String toString() {
        return "TokenRefreshResult[REDACTED]";
    }
}
