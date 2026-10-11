package com.gift.payment.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_INTERNAL_API_KEY(HttpStatus.UNAUTHORIZED, "내부 API 인증에 실패했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }
}
