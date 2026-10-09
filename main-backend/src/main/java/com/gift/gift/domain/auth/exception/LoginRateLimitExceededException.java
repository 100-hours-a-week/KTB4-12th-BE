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
                    "재시도 대기 시간은 1초 이상이어야 합니다."
            );
        }

        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
