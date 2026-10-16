package com.gift.gift.domain.order.dto.response;

import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.service.PaymentProcessingResult;

public record PaymentProcessingResponse(
        String orderId,
        OrderStatus paymentStatus,
        PaymentApprovalResult approvalResult,
        int retryAfterSeconds
) {

    public static PaymentProcessingResponse from(
            PaymentProcessingResult processing,
            int retryAfterSeconds
    ) {
        return new PaymentProcessingResponse(
                processing.orderId(),
                OrderStatus.PENDING,
                processing.approvalResult(),
                retryAfterSeconds
        );
    }
}
