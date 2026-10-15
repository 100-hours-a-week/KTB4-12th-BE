package com.gift.gift.domain.order.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.service.PaymentCancellationResult;

public record PaymentCancellationResponse(
        String orderId,
        String paymentKey,
        String transactionKey,
        OrderStatus paymentStatus,
        Long giftId,
        GiftStatus giftStatus,
        long cancelAmount,
        long balanceAfter,
        OffsetDateTime canceledAt
) {

    public static PaymentCancellationResponse from(
            PaymentCancellationResult result,
            ZoneId zoneId
    ) {
        return new PaymentCancellationResponse(
                result.orderId(),
                result.paymentKey(),
                result.transactionKey(),
                result.paymentStatus(),
                result.giftId(),
                result.giftStatus(),
                result.cancelAmount(),
                result.balanceAfter(),
                result.canceledAt()
                        .atZone(zoneId)
                        .toOffsetDateTime()
        );
    }
}
