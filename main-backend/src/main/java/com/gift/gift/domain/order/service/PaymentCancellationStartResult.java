package com.gift.gift.domain.order.service;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskClaim;

public record PaymentCancellationStartResult(
        PaymentCancellation cancellation,
        Order order,
        boolean created,
        boolean recoveryTaskManaged,
        String syncOwnerToken
) {

    public static PaymentCancellationStartResult created(
            PaymentCancellation cancellation,
            Order order,
            PaymentRecoveryTaskClaim claim
    ) {
        return new PaymentCancellationStartResult(
                cancellation,
                order,
                true,
                claim != null && claim.managed(),
                claim == null ? null : claim.token()
        );
    }

    public static PaymentCancellationStartResult existing(
            PaymentCancellation cancellation,
            Order order,
            PaymentRecoveryTaskClaim claim
    ) {
        return new PaymentCancellationStartResult(
                cancellation,
                order,
                false,
                claim != null && claim.managed(),
                claim == null ? null : claim.token()
        );
    }

    public static PaymentCancellationStartResult created(
            PaymentCancellation cancellation,
            Order order
    ) {
        return new PaymentCancellationStartResult(
                cancellation, order, true, false, null
        );
    }

    public static PaymentCancellationStartResult existing(
            PaymentCancellation cancellation,
            Order order
    ) {
        return new PaymentCancellationStartResult(
                cancellation, order, false, false, null
        );
    }
}
