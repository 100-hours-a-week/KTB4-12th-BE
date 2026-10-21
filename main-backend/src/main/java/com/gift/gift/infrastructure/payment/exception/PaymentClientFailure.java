package com.gift.gift.infrastructure.payment.exception;

public enum PaymentClientFailure {

    INVALID_REQUEST,
    AUTHENTICATION_FAILED,
    PAYMENT_NOT_FOUND,
    ORDER_ID_CONFLICT,
    IDEMPOTENCY_KEY_CONFLICT,
    PAYMENT_NOT_CANCELABLE,
    PAYMENT_DECLINED,
    CANCELLATION_DECLINED,
    PAYMENT_SERVER_UNAVAILABLE,
    PAYMENT_RESULT_UNCONFIRMED,
    CANCELLATION_RESULT_UNCONFIRMED,
    INVALID_RESPONSE;

    public static PaymentClientFailure from(String errorCode) {
        if (errorCode == null) {
            return INVALID_RESPONSE;
        }

        return switch (errorCode) {
            case "INVALID_REQUEST" -> INVALID_REQUEST;
            case "INVALID_INTERNAL_API_KEY" -> AUTHENTICATION_FAILED;
            case "PAYMENT_NOT_FOUND" -> PAYMENT_NOT_FOUND;
            case "ORDER_ID_CONFLICT" -> ORDER_ID_CONFLICT;
            case "IDEMPOTENCY_KEY_CONFLICT" -> IDEMPOTENCY_KEY_CONFLICT;
            case "PAYMENT_NOT_CANCELABLE" -> PAYMENT_NOT_CANCELABLE;
            case "PAYMENT_DECLINED" -> PAYMENT_DECLINED;
            case "CANCELLATION_DECLINED" -> CANCELLATION_DECLINED;
            case "TEMPORARY_PROCESSING_ERROR",
                 "INTERNAL_SERVER_ERROR" ->
                    PAYMENT_SERVER_UNAVAILABLE;
            default -> INVALID_RESPONSE;
        };
    }
}
