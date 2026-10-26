package com.gift.gift.domain.order.service;

import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;

public record OrderRewardSummary(
        Integer couponMultiplier,
        OrderCouponUsageStatus couponStatus,
        Long expectedRewardAmount,
        Long rewardAmount
) {
}
