package com.gift.gift.domain.order.service;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskClaim;

public record PaymentStartResult(
        Order order,
        boolean created,
        boolean recoveryTaskManaged,
        String syncOwnerToken
) {

    public static PaymentStartResult created(Order order, String token) {
        return new PaymentStartResult(order, true, true, token);
    }

    public static PaymentStartResult existing(Order order, PaymentRecoveryTaskClaim claim) {
        return claim == null
                ? new PaymentStartResult(order, false, false, null)
                : new PaymentStartResult(order, false, claim.managed(), claim.token());
    }

    // 테스트와 작업 원장이 없는 기존 데이터 경로에서 사용한다.
    public static PaymentStartResult created(Order order) {
        return new PaymentStartResult(order, true, false, null);
    }

    public static PaymentStartResult existing(Order order) {
        return new PaymentStartResult(order, false, false, null);
    }
}
