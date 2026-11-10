package com.gift.gift.domain.coupon.dto.response;

import java.util.List;

import com.gift.gift.domain.gift.support.GiftPolicy;

public record CouponListResponse(
        List<CouponResponse> coupons,
        long baseRewardAmount
) {

    public static CouponListResponse from(List<CouponResponse> coupons) {
        return new CouponListResponse(List.copyOf(coupons), GiftPolicy.BASE_REWARD_AMOUNT);
    }
}
