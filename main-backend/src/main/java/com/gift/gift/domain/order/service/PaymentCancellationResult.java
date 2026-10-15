package com.gift.gift.domain.order.service;

import java.time.LocalDateTime;

import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.entity.OrderStatus;

public record PaymentCancellationResult(
        String orderId,
        String paymentKey,
        String transactionKey,
        OrderStatus paymentStatus,
        Long giftId,
        GiftStatus giftStatus,
        long cancelAmount,
        long balanceAfter,
        LocalDateTime canceledAt,
        boolean created
) {
}
