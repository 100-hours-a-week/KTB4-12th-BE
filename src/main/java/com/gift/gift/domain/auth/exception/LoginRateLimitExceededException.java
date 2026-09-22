package com.gift.gift.domain.auth.exception;

public class LoginRateLimitExceededException
        extends AuthException {

    private final long retryAfterSeconds;

    public LoginRateLimitExceededException(
            long retryAfterSeconds
    ) {
        super(AuthErrorCode.LOGIN_RATE_LIMIT_EXCEEDED);

        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException(
                    "retryAfterSeconds must be positive"
            );
        }

        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
