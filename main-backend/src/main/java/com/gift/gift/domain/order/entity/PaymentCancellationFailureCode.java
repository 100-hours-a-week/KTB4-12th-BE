package com.gift.gift.domain.order.entity;

public enum PaymentCancellationFailureCode {
    CANCELLATION_DECLINED,
    CANCELLATION_RESULT_UNCONFIRMED,
    PAYMENT_SERVER_UNAVAILABLE,
    LOCAL_COMPENSATION_FAILED
}
