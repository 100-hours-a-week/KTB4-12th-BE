package com.gift.gift.domain.credit.response;

import org.springframework.http.HttpStatus;

public enum CreditSuccessCode {

    BALANCE_RETRIEVED(HttpStatus.OK, "크레딧 잔액을 조회했습니다.");

    private final HttpStatus status;
    private final String message;

    CreditSuccessCode(HttpStatus status, String message) {
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
