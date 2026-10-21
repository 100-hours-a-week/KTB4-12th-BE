package com.gift.gift.global.exception;

public class RequestValidationException extends RuntimeException {

    private final ErrorCode errorCode;

    public RequestValidationException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
