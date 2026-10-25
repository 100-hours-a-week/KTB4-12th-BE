package com.gift.gift.domain.coupon.dto.response;

import java.util.List;

public record CouponListResponse(
        List<CouponResponse> coupons
) {

    public static CouponListResponse from(List<CouponResponse> coupons) {
        return new CouponListResponse(List.copyOf(coupons));
    }
}
