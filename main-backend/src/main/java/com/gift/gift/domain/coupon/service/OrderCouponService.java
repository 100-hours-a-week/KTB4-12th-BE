package com.gift.gift.domain.coupon.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.entity.CouponInventory;
import com.gift.gift.domain.coupon.entity.OrderCouponUsage;
import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;
import com.gift.gift.domain.coupon.repository.CouponInventoryRepository;
import com.gift.gift.domain.coupon.repository.OrderCouponUsageRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class OrderCouponService {

    private final OrderRepository orderRepository;
    private final CouponInventoryRepository inventoryRepository;
    private final OrderCouponUsageRepository usageRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long orderRecordId, int multiplier) {
        if (multiplier != 2 && multiplier != 3) {
            throw new IllegalArgumentException(
                    "쿠폰 배수는 2 또는 3이어야 합니다."
            );
        }

        Order order = findOrderForUpdate(orderRecordId);

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "현재 주문 상태에서는 쿠폰을 예약할 수 없습니다."
            );
        }

        CouponInventory inventory = inventoryRepository
                .findByUserIdAndMultiplier(
                        order.getPayerId(),
                        multiplier
                )
                .orElseThrow(() -> new IllegalStateException(
                        "사용자의 쿠폰 초기 행이 존재하지 않습니다."
                ));

        OrderCouponUsage existing = usageRepository
                .findByOrderRecordId(orderRecordId)
                .orElse(null);

        if (existing != null) {
            if (!existing.getCouponInventoryId().equals(inventory.getId())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "주문에 이미 예약한 쿠폰과 선택한 쿠폰이 다릅니다."
                );
            }

            if (existing.getStatus() == OrderCouponUsageStatus.RESERVED) {
                return;
            }

            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "이미 처리한 주문의 쿠폰을 다시 예약할 수 없습니다."
            );
        }

        int decreased = inventoryRepository.decreaseQuantity(
                inventory.getId(),
                LocalDateTime.now(clock)
        );

        if (decreased != 1) {
            throw new BusinessException(ErrorCode.COUPON_NOT_AVAILABLE);
        }

        usageRepository.saveAndFlush(new OrderCouponUsage(
                orderRecordId,
                inventory.getId()
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreAfterFailure(Long orderRecordId) {
        Order order = findOrderForUpdate(orderRecordId);

        boolean declined = order.getStatus() == OrderStatus.DECLINED;
        boolean automaticallyCanceled =
                order.getStatus() == OrderStatus.CANCELED
                        && order.getFailureCode()
                        == OrderFailureCode.LOCAL_PROCESSING_FAILED;

        if (!declined && !automaticallyCanceled) {
            throw new IllegalStateException(
                    "실패가 확정되지 않은 주문의 쿠폰은 복구할 수 없습니다."
            );
        }

        OrderCouponUsage usage = usageRepository
                .findByOrderRecordId(orderRecordId)
                .orElse(null);

        if (usage == null) {
            return;
        }

        if (usage.getStatus() == OrderCouponUsageStatus.RESTORED) {
            return;
        }

        if (usage.getStatus() != OrderCouponUsageStatus.RESERVED) {
            throw new IllegalStateException(
                    "사용 완료된 쿠폰은 실패 예약 복구로 처리할 수 없습니다."
            );
        }

        LocalDateTime now = LocalDateTime.now(clock);

        int restored = usageRepository.restoreReserved(
                usage.getId(),
                OrderCouponUsageStatus.RESERVED,
                OrderCouponUsageStatus.RESTORED,
                now
        );

        if (restored != 1) {
            throw new IllegalStateException(
                    "주문의 쿠폰 예약 상태를 복구할 수 없습니다."
            );
        }

        int increased = inventoryRepository.restoreQuantity(
                usage.getCouponInventoryId(),
                now
        );

        if (increased != 1) {
            throw new IllegalStateException(
                    "사용자의 쿠폰 수량을 복구할 수 없습니다."
            );
        }
    }

    private Order findOrderForUpdate(Long orderRecordId) {
        if (orderRecordId == null || orderRecordId <= 0) {
            throw new IllegalArgumentException(
                    "주문 레코드 ID는 양수여야 합니다."
            );
        }

        return orderRepository.findByIdForUpdate(orderRecordId)
                .orElseThrow(() -> new IllegalStateException(
                        "쿠폰을 처리할 주문이 존재하지 않습니다."
                ));
    }
}
