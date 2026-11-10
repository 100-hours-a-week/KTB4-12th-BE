package com.gift.gift.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;
import com.gift.gift.domain.coupon.repository.OrderCouponUsageRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.support.GiftPolicy;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;

@Service
@RequiredArgsConstructor
public class OrderRewardQueryService {

    private final OrderCouponUsageRepository usageRepository;
    private final CreditTransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public OrderRewardSummary summarize(Order order, GiftHistory gift) {
        // 벌크 사용 확정 직후에도 영속성 컨텍스트의 이전 RESERVED 상태를 재사용하지 않는다.
        var selection = usageRepository.findSelectionByOrderRecordId(order.getId()).orElse(null);
        Integer multiplier = selection == null ? null : selection.getMultiplier();
        OrderCouponUsageStatus couponStatus = selection == null ? null : selection.getStatus();

        Long rewardAmount = gift == null ? null : transactionRepository
                .findByDeduplicationKey("GIFT_COMPLETED_REWARD:" + gift.getId() + ":" + order.getPayerId())
                .map(transaction -> transaction.getAmount())
                .orElse(null);

        if (gift != null && gift.getStatus() == GiftStatus.COMPLETED && rewardAmount == null) {
            throw new IllegalStateException("수락 완료된 선물의 보상 원장이 존재하지 않습니다.");
        }

        Long expectedRewardAmount = null;
        if (rewardAmount == null && order.getStatus() == OrderStatus.COMPLETED
                && gift != null && gift.getStatus() == GiftStatus.WAITING_FOR_ACCEPTANCE) {
            if (selection != null && couponStatus != OrderCouponUsageStatus.USED) {
                throw new IllegalStateException("완료된 결제의 쿠폰이 사용 확정되지 않았습니다.");
            }
            expectedRewardAmount = Math.multiplyExact(GiftPolicy.BASE_REWARD_AMOUNT,
                    multiplier == null ? 1 : multiplier);
        }

        return new OrderRewardSummary(multiplier, couponStatus, expectedRewardAmount, rewardAmount);
    }
}
