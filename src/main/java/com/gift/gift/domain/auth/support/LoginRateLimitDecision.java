package com.gift.gift.domain.auth.support;

public record LoginRateLimitDecision(
        boolean permitted,
        long retryAfterSeconds
) {
    public LoginRateLimitDecision {
        if (permitted && retryAfterSeconds != 0) {
            throw new IllegalArgumentException(
                    "허용 결정의 재시도 대기 시간은 0초여야 합니다."
            );
        }

        if (!permitted && retryAfterSeconds < 1) {
            throw new IllegalArgumentException(
                    "거부 결정의 재시도 대기 시간은 1초 이상이어야 합니다."
            );
        }
    }

    public static LoginRateLimitDecision permit() {
        return new LoginRateLimitDecision(true, 0);
    }

    public static LoginRateLimitDecision reject(long retryAfterSeconds) {
        return new LoginRateLimitDecision(false, retryAfterSeconds);
    }
}
