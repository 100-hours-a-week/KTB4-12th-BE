package com.gift.gift.domain.user.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class LoginRateLimitExceededException
        extends BusinessException {

    private final long retryAfterSeconds;

    public LoginRateLimitExceededException(
            long retryAfterSeconds
    ) {
        super(ErrorCode.TOO_MANY_REQUESTS);

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
