package com.gift.gift.domain.coupon.response;

import org.springframework.http.HttpStatus;

public enum CouponSuccessCode {

    COUPONS_RETRIEVED(HttpStatus.OK, "보유 쿠폰을 조회했습니다.");

    private final HttpStatus status;
    private final String message;

    CouponSuccessCode(HttpStatus status, String message) {
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
