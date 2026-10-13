package com.gift.gift.domain.order.service;

public record PaymentValidation(
        long unitPrice,
        long amount
) {
}
