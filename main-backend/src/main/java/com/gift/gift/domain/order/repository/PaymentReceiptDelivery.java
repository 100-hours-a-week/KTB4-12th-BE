package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;

public record PaymentReceiptDelivery(
        PaymentReceiptDeliveryStatus status,
        int autoAttempts,
        String claimToken,
        LocalDateTime leaseUntil,
        boolean leaseActive,
        String replayToken,
        LocalDateTime replayUsedAt
) {
}
