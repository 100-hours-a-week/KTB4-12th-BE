package com.gift.gift.infrastructure.payment.dto;

public record PaymentConfirmRequest(
        String orderId,
        Long amount
) {
}
