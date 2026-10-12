package com.gift.gift.infrastructure.payment.dto;

import java.time.LocalDateTime;

public record PaymentResponse(
        String orderId,
        String paymentKey,
        String transactionKey,
        PaymentServerStatus status,
        Long amount,
        LocalDateTime approvedAt
) {
}
