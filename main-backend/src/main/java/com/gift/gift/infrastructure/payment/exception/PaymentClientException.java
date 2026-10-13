package com.gift.gift.infrastructure.payment.exception;

public class PaymentClientException extends RuntimeException {

    private final PaymentClientFailure failure;
    private final String upstreamTraceId;

    public PaymentClientException(
            PaymentClientFailure failure,
            String message,
            String upstreamTraceId
    ) {
        super(message);
        this.failure = failure;
        this.upstreamTraceId = upstreamTraceId;
    }

    public PaymentClientException(
            PaymentClientFailure failure,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failure = failure;
        this.upstreamTraceId = null;
    }

    public PaymentClientFailure failure() {
        return failure;
    }

    public String upstreamTraceId() {
        return upstreamTraceId;
    }
}
