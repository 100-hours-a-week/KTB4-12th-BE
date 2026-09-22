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
                "액세스 토큰은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                refreshToken,
                "리프레시 토큰은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                userId,
                "사용자 식별자는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                name,
                "사용자 이름은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                email,
                "이메일은 null일 수 없습니다."
        );

        if (expiresIn <= 0) {
            throw new IllegalArgumentException(
                    "토큰 유효 시간은 1초 이상이어야 합니다."
            );
        }
    }

    @Override
    public String toString() {
        return "LoginResult[REDACTED]";
    }
}
