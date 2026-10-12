package com.gift.payment.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),

    INVALID_INTERNAL_API_KEY(HttpStatus.UNAUTHORIZED, "내부 API 인증에 실패했습니다."),

    ORDER_ID_CONFLICT(HttpStatus.CONFLICT, "같은 주문번호로 다른 결제를 요청할 수 없습니다."),

    PAYMENT_DECLINED(HttpStatus.UNPROCESSABLE_ENTITY, "결제가 거절되었습니다."),

    TEMPORARY_PROCESSING_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "결제를 일시적으로 처리할 수 없습니다.");

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
