package com.gift.gift.domain.coupon.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.repository.CouponInventoryRepository;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponInventoryRepository couponInventoryRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(Long userId, int multiplier) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }

        if (multiplier != 2 && multiplier != 3) {
            throw new IllegalArgumentException("쿠폰 배수는 2 또는 3이어야 합니다.");
        }

        int updated = couponInventoryRepository.increaseQuantity(
                userId,
                multiplier,
                LocalDateTime.now(clock)
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "쿠폰 초기 행이 없거나 보유 수량 한도를 초과했습니다."
            );
        }
    }
}
