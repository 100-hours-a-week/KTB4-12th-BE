package com.gift.gift.domain.coupon.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.entity.CouponInventory;
import com.gift.gift.domain.coupon.repository.CouponInventoryRepository;

@Service
@RequiredArgsConstructor
public class CouponInitializationService {

    private final CouponInventoryRepository couponInventoryRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Long userId) {
        couponInventoryRepository.saveAllAndFlush(List.of(
                new CouponInventory(userId, 2),
                new CouponInventory(userId, 3)
        ));
    }
}
