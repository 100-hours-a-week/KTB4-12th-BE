package com.gift.gift.domain.order.event;

import java.time.LocalDateTime;
import java.util.Objects;

public record PaymentReceiptEmailEvent(
        String recipientEmail,
        String orderId,
        String productName,
        int quantity,
        long creditAmount,
        long balanceAfter,
        LocalDateTime completedAt
) {

    public PaymentReceiptEmailEvent {
        Objects.requireNonNull(recipientEmail, "수신 이메일은 null일 수 없습니다.");
        Objects.requireNonNull(orderId, "주문 번호는 null일 수 없습니다.");
        Objects.requireNonNull(productName, "상품명은 null일 수 없습니다.");
        Objects.requireNonNull(completedAt, "결제 완료 시각은 null일 수 없습니다.");
    }
}
