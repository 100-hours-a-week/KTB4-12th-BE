package com.gift.gift.domain.order.event;

import java.util.Objects;

public record PaymentCanceledEvent(Long orderRecordId, Long payerId, Long giftId, String productName) {

    public PaymentCanceledEvent {

        Objects.requireNonNull(orderRecordId, "주문 레코드 ID는 null일 수 없습니다.");

        Objects.requireNonNull(payerId, "발신인 ID는 null일 수 없습니다.");

        Objects.requireNonNull(giftId, "선물 ID는 null일 수 없습니다.");

        Objects.requireNonNull(productName, "상품명은 null일 수 없습니다.");
    }
}
