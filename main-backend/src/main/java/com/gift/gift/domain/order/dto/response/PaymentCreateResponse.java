package com.gift.gift.domain.order.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.service.PaymentCompletion;

public record PaymentCreateResponse(
        String orderId,
        String paymentKey,
        String transactionKey,
        OrderStatus paymentStatus,
        Long giftId,
        GiftStatus giftStatus,
        long amount,
        long balanceAfter,
        OffsetDateTime approvedAt,
        OffsetDateTime giftExpiresAt
) {

    public static PaymentCreateResponse from(PaymentCompletion completion, ZoneId zoneId) {

        return new PaymentCreateResponse(
                completion.orderId(),
                completion.paymentKey(),
                completion.transactionKey(),
                completion.paymentStatus(),
                completion.giftId(),
                completion.giftStatus(),
                completion.amount(),
                completion.balanceAfter(),
                completion.approvedAt().atZone(zoneId).toOffsetDateTime(),
                completion.giftExpiresAt().atZone(zoneId).toOffsetDateTime()
        );
    }
}
