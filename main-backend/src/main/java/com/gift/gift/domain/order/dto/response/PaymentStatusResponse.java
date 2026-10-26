package com.gift.gift.domain.order.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.service.OrderRewardSummary;

public record PaymentStatusResponse(
        String orderId,
        OrderStatus paymentStatus,
        PaymentApprovalResult approvalResult,
        OrderFailureCode failureCode,
        Long giftId,
        GiftStatus giftStatus,
        long amount,
        OffsetDateTime approvedAt,
        OffsetDateTime canceledAt,
        Integer couponMultiplier,
        OrderCouponUsageStatus couponStatus,
        Long expectedRewardAmount,
        Long rewardAmount
) {

    public static PaymentStatusResponse from(
            Order order, GiftHistory gift, OrderRewardSummary reward, ZoneId zoneId
    ) {
        return new PaymentStatusResponse(
                order.getOrderId(),
                order.getStatus(),
                order.getApprovalResult(),
                order.getFailureCode(),
                gift == null ? null : gift.getId(),
                gift == null ? null : gift.getStatus(),
                order.getAmount(),
                toOffset(order.getApprovedAt(), zoneId),
                toOffset(order.getCanceledAt(), zoneId),
                reward.couponMultiplier(),
                reward.couponStatus(),
                reward.expectedRewardAmount(),
                reward.rewardAmount()
        );
    }

    private static OffsetDateTime toOffset(LocalDateTime dateTime, ZoneId zoneId) {
        return dateTime == null
                ? null
                : dateTime.atZone(zoneId).toOffsetDateTime();
    }
}
