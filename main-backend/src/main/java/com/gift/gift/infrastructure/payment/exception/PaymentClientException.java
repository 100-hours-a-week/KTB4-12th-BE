package com.gift.gift.infrastructure.payment.exception;

public class PaymentClientException extends RuntimeException {

    private final PaymentClientFailure failure;
    private final String upstreamTraceId;
    private final boolean outcomeUncertain;

    public PaymentClientException(
            PaymentClientFailure failure,
            String message,
            String upstreamTraceId
    ) {
        this(failure, message, upstreamTraceId, null, false);
    }

    public PaymentClientException(
            PaymentClientFailure failure,
            String message,
            Throwable cause
    ) {
        this(failure, message, null, cause, false);
    }

    private PaymentClientException(
            PaymentClientFailure failure,
            String message,
            String upstreamTraceId,
            Throwable cause,
            boolean outcomeUncertain
    ) {
        super(message, cause);
        this.failure = failure;
        this.upstreamTraceId = upstreamTraceId;
        this.outcomeUncertain = outcomeUncertain;
    }

    public PaymentClientFailure failure() {
        return failure;
    }

    public String upstreamTraceId() {
        return upstreamTraceId;
    }

    public boolean outcomeUncertain() {
        return outcomeUncertain;
    }

    public PaymentClientException withUncertainOutcome() {
        return new PaymentClientException(
                failure, getMessage(), upstreamTraceId, this, true
        );
    }
}
