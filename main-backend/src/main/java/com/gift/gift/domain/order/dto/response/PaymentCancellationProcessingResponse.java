package com.gift.gift.domain.order.dto.response;

import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.entity.OrderStatus;

public record PaymentCancellationProcessingResponse(
        String orderId,
        OrderStatus paymentStatus,
        Long cancellationId,
        PaymentCancellationStatus cancellationStatus,
        PaymentCancellationFailureCode cancellationFailureCode,
        String statusUrl,
        int retryAfterSeconds
) {

    public static PaymentCancellationProcessingResponse from(
            PaymentStatusResponse status,
            int retryAfterSeconds
    ) {
        return new PaymentCancellationProcessingResponse(
                status.orderId(),
                status.paymentStatus(),
                status.cancellationId(),
                status.cancellationStatus(),
                status.cancellationFailureCode(),
                "/payments/" + status.orderId(),
                retryAfterSeconds
        );
    }
}
