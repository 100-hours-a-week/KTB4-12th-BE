package com.gift.gift.domain.order.exception;

import com.gift.gift.global.exception.ErrorCode;

public enum OrderErrorCode {

    INVALID_PAYMENT_REQUEST(
            ErrorCode.INVALID_REQUEST
    ),
    PRODUCT_NOT_FOUND(
            ErrorCode.PRODUCT_NOT_FOUND
    ),
    INSUFFICIENT_STOCK(
            ErrorCode.INSUFFICIENT_STOCK
    ),
    INSUFFICIENT_CREDIT(
            ErrorCode.INSUFFICIENT_CREDIT
    ),
    PAYMENT_NOT_FOUND(
            ErrorCode.PAYMENT_NOT_FOUND
    ),
    PAYMENT_DECLINED(
            ErrorCode.PAYMENT_DECLINED
    ),
    PAYMENT_RESULT_UNCONFIRMED(
            ErrorCode.PAYMENT_RESULT_UNCONFIRMED
    ),
    PAYMENT_STATE_CONFLICT(
            ErrorCode.CONFLICT,
            "현재 주문 상태에서는 결제를 완료할 수 없습니다."
    ),
    IDEMPOTENCY_KEY_CONFLICT(
            ErrorCode.IDEMPOTENCY_KEY_CONFLICT
    ),
    PAYMENT_NOT_CANCELABLE(
            ErrorCode.PAYMENT_NOT_CANCELABLE
    ),
    CANCELLATION_DECLINED(
            ErrorCode.CANCELLATION_DECLINED
    ),
    CANCELLATION_RESULT_UNCONFIRMED(
            ErrorCode.CANCELLATION_RESULT_UNCONFIRMED
    ),
    CANCELLATION_PROCESSING_FAILED(
            ErrorCode.INTERNAL_SERVER_ERROR,
            "결제 취소를 처리하지 못했습니다. 다시 시도해 주세요."
    ),
    PAYMENT_PROCESSING_FAILED(
            ErrorCode.INTERNAL_SERVER_ERROR,
            "결제를 완료하지 못했습니다. 다시 시도해 주세요."
    );

    private final ErrorCode errorCode;
    private final String message;

    OrderErrorCode(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    OrderErrorCode(ErrorCode errorCode, String message) {
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
