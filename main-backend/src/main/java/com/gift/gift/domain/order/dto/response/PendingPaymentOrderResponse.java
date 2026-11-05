package com.gift.gift.domain.order.dto.response;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;

public record PendingPaymentOrderResponse(String orderId, OrderStatus paymentStatus) {

    public static PendingPaymentOrderResponse from(Order order) {
        return new PendingPaymentOrderResponse(
                order.getOrderId(),
                order.getStatus()
        );
    }
}
