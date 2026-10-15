package com.gift.gift.domain.order.event;

import java.util.Objects;

public record PaymentCompletedEvent(Long orderRecordId, Long payerId, Long giftId, String productName) {

    public PaymentCompletedEvent {

        Objects.requireNonNull(orderRecordId, "주문 레코드 ID는 null일 수 없습니다.");

        Objects.requireNonNull(payerId, "결제자 ID는 null일 수 없습니다.");

        Objects.requireNonNull(giftId, "선물 ID는 null일 수 없습니다.");

        Objects.requireNonNull(productName, "상품명은 null일 수 없습니다.");
    }
}
