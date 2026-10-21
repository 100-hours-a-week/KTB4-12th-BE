package com.gift.gift.domain.order.service;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;

public record PaymentCancellationStartResult(
        PaymentCancellation cancellation,
        Order order,
        boolean created
) {

    public static PaymentCancellationStartResult created(PaymentCancellation cancellation, Order order) {
        return new PaymentCancellationStartResult(
                cancellation,
                order,
                true
        );
    }

    public static PaymentCancellationStartResult existing(PaymentCancellation cancellation, Order order) {
        return new PaymentCancellationStartResult(
                cancellation,
                order,
                false
        );
    }
}
