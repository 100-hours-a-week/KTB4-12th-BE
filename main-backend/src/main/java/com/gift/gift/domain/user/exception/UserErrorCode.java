package com.gift.gift.domain.user.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum UserErrorCode {

    SIGNUP_TERMS_NOT_FOUND(ErrorCode.SIGNUP_TERMS_NOT_FOUND),
    INVALID_EMAIL_FORMAT(ErrorCode.INVALID_EMAIL_FORMAT),
    INVALID_USER_SEARCH_REQUEST(
            ErrorCode.INVALID_REQUEST,
            "이메일 형식을 확인해 주세요."
    ),
    EMAIL_ALREADY_IN_USE(ErrorCode.EMAIL_ALREADY_IN_USE),
    INVALID_TERM_VERSION(ErrorCode.INVALID_TERM_VERSION),
    REQUIRED_TERMS_NOT_AGREED(ErrorCode.REQUIRED_TERMS_NOT_AGREED),
    USER_NOT_FOUND(ErrorCode.USER_NOT_FOUND);

    private final ErrorCode errorCode;
    private final String message;

    UserErrorCode(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    UserErrorCode(ErrorCode errorCode, String message) {
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
