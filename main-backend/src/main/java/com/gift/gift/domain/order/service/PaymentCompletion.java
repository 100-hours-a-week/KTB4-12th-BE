package com.gift.gift.domain.order.service;

import java.time.LocalDateTime;

import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.entity.OrderStatus;

public record PaymentCompletion(
        String orderId,
        String paymentKey,
        String transactionKey,
        OrderStatus paymentStatus,
        Long giftId,
        GiftStatus giftStatus,
        long amount,
        long balanceAfter,
        LocalDateTime approvedAt,
        LocalDateTime giftExpiresAt
) {
}
