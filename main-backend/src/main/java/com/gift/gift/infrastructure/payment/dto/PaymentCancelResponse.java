package com.gift.gift.infrastructure.payment.dto;

import java.time.LocalDateTime;

public record PaymentCancelResponse(
        String orderId,
        String paymentKey,
        String transactionKey,
        PaymentServerStatus status,
        Long cancelAmount,
        LocalDateTime canceledAt
) {
}
