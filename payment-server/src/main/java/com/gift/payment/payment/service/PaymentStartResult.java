package com.gift.payment.payment.service;

import java.util.Objects;

import com.gift.payment.payment.entity.Payment;

// orderId + requestHash
// Main Backend 서버로부터 승인 요청을 받은 후 Payment Server의 payments 레코드를 생성했는지 나타냄.
public record PaymentStartResult(Payment payment, boolean created) {

    public PaymentStartResult {
        Objects.requireNonNull(payment, "결제 정보는 null일 수 없습니다.");
    }

    public static PaymentStartResult created(Payment payment) {
        return new PaymentStartResult(payment, true);
    }

    public static PaymentStartResult existing(Payment payment) {
        return new PaymentStartResult(payment, false);
    }
}
