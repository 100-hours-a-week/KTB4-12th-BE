package com.gift.gift.domain.user.exception;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public class LoginRateLimitExceededException
        extends BusinessException {

    private static final String LOGIN_RATE_LIMIT_MESSAGE =
            "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    private final long retryAfterSeconds;

    public LoginRateLimitExceededException(
            long retryAfterSeconds
    ) {
        super(
                ErrorCode.TOO_MANY_REQUESTS,
                LOGIN_RATE_LIMIT_MESSAGE
        );

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
