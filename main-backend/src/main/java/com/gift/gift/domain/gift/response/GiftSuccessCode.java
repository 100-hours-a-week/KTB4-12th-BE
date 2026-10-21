package com.gift.gift.domain.gift.response;

import org.springframework.http.HttpStatus;

public enum GiftSuccessCode {

    GIFT_ACCEPTED(
            HttpStatus.OK,
            "선물을 받았습니다."
    );

    private final HttpStatus status;
    private final String message;

    GiftSuccessCode(HttpStatus status, String message) {
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
