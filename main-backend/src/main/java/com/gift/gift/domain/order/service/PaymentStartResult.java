package com.gift.gift.domain.order.service;

import com.gift.gift.domain.order.entity.Order;

// paymentId + Idempotency-Key + requestHash
// 사용자의 결제 요청을 받아 로컬 Orders 테이블에 PENDING 주문을 생성했는지 알려주는 역할
public record PaymentStartResult(Order order, boolean created) {

    public static PaymentStartResult created(Order order) {
        return new PaymentStartResult(order, true);
    }

    public static PaymentStartResult existing(Order order) {
        return new PaymentStartResult(order, false);
    }
}
