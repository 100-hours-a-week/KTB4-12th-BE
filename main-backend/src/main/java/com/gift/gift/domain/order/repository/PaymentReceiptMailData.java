package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;

public record PaymentReceiptMailData(
        String recipientEmail,
        String orderId,
        String productName,
        int quantity,
        long amount,
        long balanceAfter,
        LocalDateTime paymentCompletedAt,
        LocalDateTime canceledAt
) {
}
