package com.gift.gift.domain.order.response;

import org.springframework.http.HttpStatus;

public enum OrderSuccessCode {

    PAYMENT_CREATED(
            HttpStatus.CREATED,
            "결제가 완료되었습니다."
    ),
    PAYMENT_REUSED(
            HttpStatus.OK,
            "결제가 완료되었습니다."
    );

    private final HttpStatus status;
    private final String message;

    OrderSuccessCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
