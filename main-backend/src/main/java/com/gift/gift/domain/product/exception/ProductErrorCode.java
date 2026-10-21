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
    ),

    RECIPIENT_NOT_FOUND(
            ErrorCode.RECIPIENT_NOT_FOUND,
            "선택한 수신자 정보를 확인할 수 없습니다."
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
