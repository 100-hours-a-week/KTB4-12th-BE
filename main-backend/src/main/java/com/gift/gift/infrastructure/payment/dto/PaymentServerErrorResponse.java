package com.gift.gift.infrastructure.payment.dto;

public record PaymentServerErrorResponse(String message, ErrorBody error) {

    public record ErrorBody(String code, String traceId) {
    }
}
