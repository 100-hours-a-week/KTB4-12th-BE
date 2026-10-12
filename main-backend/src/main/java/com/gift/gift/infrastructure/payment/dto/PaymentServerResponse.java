package com.gift.gift.infrastructure.payment.dto;

public record PaymentServerResponse<T>(
        String message,
        T data
) {
}
