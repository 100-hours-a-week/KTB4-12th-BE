package com.gift.gift.domain.user.support;

public record LoginRateLimitDecision(
        boolean permitted,
        long retryAfterSeconds
) {
    public LoginRateLimitDecision {
        if (permitted && retryAfterSeconds != 0) {
            throw new IllegalArgumentException(
                    "Permitted decision must have zero retryAfterSeconds"
            );
        }

        if (!permitted && retryAfterSeconds < 1) {
            throw new IllegalArgumentException(
                    "Rejected decision must have positive retryAfterSeconds"
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
