package com.gift.gift.domain.order.service;

import java.time.LocalDateTime;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;

public record PaymentCancellationApproval(
        PaymentCancellation cancellation,
        Order order,
        String transactionKey,
        Long cancelAmount,
        LocalDateTime canceledAt,
        boolean created,
        boolean recoveryTaskManaged,
        String syncOwnerToken
) {
    public PaymentCancellationApproval(
            PaymentCancellation cancellation,
            Order order,
            String transactionKey,
            Long cancelAmount,
            LocalDateTime canceledAt,
            boolean created
    ) {
        this(
                cancellation,
                order,
                transactionKey,
                cancelAmount,
                canceledAt,
                created,
                false,
                null
        );
    }
}
