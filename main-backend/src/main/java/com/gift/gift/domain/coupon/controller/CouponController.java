package com.gift.gift.domain.coupon.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.coupon.dto.response.CouponListResponse;
import com.gift.gift.domain.coupon.response.CouponSuccessCode;
import com.gift.gift.domain.coupon.service.CouponQueryService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponQueryService couponQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<CouponListResponse>> getCoupons(
            @CurrentUserId Long userId
    ) {
        CouponListResponse response = couponQueryService.getCoupons(userId);
        CouponSuccessCode successCode = CouponSuccessCode.COUPONS_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(successCode.message(), response));
    }
}
