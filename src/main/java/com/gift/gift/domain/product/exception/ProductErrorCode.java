package com.gift.gift.domain.product.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum ProductErrorCode {

    INVALID_REQUEST(
            ErrorCode.INVALID_REQUEST,
            "조회 조건을 확인해 주세요."
    ),

    INVALID_PRODUCT_ID(
            ErrorCode.INVALID_REQUEST,
            "상품 식별자를 확인해 주세요."
    );

    private final ErrorCode errorCode;
    private final String message;

    ProductErrorCode(ErrorCode errorCode, String message) {
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
