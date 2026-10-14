package com.gift.payment.payment.dto.response;

import java.time.LocalDateTime;
import java.util.Objects;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.entity.PaymentStatus;

public record PaymentCancelResponse(
        String orderId,
        String paymentKey,
        String transactionKey,
        PaymentStatus status,
        Long cancelAmount,
        LocalDateTime canceledAt
) {

    public static PaymentCancelResponse from(PaymentCancel cancellation) {

        Objects.requireNonNull(cancellation, "결제 취소 정보는 null일 수 없습니다.");

        Payment payment = Objects.requireNonNull(cancellation.getPayment(), "원결제 정보는 null일 수 없습니다.");

        return new PaymentCancelResponse(
                payment.getOrderId(),
                payment.getPaymentKey(),
                cancellation.getTransactionKey(),
                payment.getStatus(),
                cancellation.getCancelAmount(),
                cancellation.getCanceledAt()
        );
    }
}
