package com.gift.gift.domain.coupon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.dto.response.CouponListResponse;
import com.gift.gift.domain.coupon.repository.CouponInventoryRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponQueryService {

    private final CouponInventoryRepository couponInventoryRepository;

    public CouponListResponse getCoupons(Long userId) {
        return CouponListResponse.from(
                couponInventoryRepository.findAvailableCoupons(userId)
        );
    }
}
