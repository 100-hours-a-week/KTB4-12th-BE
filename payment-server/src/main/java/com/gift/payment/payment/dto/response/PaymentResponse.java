package com.gift.payment.payment.dto.response;

import java.time.LocalDateTime;
import java.util.Objects;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentStatus;

public record PaymentResponse(
        String orderId,
        String paymentKey,
        String transactionKey,
        PaymentStatus status,
        Long amount,
        LocalDateTime approvedAt
) {

    public static PaymentResponse from(Payment payment) {

        Objects.requireNonNull(payment, "결제 정보는 null일 수 없습니다.");

        return new PaymentResponse(
                payment.getOrderId(),
                payment.getPaymentKey(),
                payment.getApprovedTransactionKey(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getApprovedAt()
        );
    }
}
