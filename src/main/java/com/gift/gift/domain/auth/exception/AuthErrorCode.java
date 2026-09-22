package com.gift.gift.domain.auth.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum AuthErrorCode {

    INVALID_CREDENTIALS(ErrorCode.INVALID_CREDENTIALS),
    LOGIN_RATE_LIMIT_EXCEEDED(
            ErrorCode.TOO_MANY_REQUESTS,
            "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
    ),
    AUTHENTICATION_TEMPORARILY_UNAVAILABLE(
            ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
    ),
    INVALID_REFRESH_TOKEN(ErrorCode.INVALID_REFRESH_TOKEN),
    TOKEN_REFRESH_CONFLICT(ErrorCode.TOKEN_REFRESH_CONFLICT);

    private final ErrorCode errorCode;
    private final String message;

    AuthErrorCode(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    AuthErrorCode(ErrorCode errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }
}
