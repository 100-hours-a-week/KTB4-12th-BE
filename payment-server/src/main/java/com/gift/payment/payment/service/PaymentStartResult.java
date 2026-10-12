package com.gift.payment.payment.service;

import java.util.Objects;

import com.gift.payment.payment.entity.Payment;

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
