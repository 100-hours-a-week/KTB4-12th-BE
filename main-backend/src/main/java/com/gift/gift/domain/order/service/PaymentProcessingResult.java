package com.gift.gift.domain.order.service;

import java.util.Objects;

import com.gift.gift.domain.order.entity.PaymentApprovalResult;

public record PaymentProcessingResult(
        String orderId,
        PaymentApprovalResult approvalResult
) {

    public PaymentProcessingResult {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("처리 중인 주문번호는 필수입니다.");
        }

        Objects.requireNonNull(approvalResult, "결제 승인 결과는 필수입니다.");
    }
}
