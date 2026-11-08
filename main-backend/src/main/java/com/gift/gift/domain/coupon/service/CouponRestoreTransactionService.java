package com.gift.gift.domain.coupon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
public class CouponRestoreTransactionService {

    private final PaymentCancellationRepository cancellationRepository;
    private final OrderCouponService orderCouponService;

    @Transactional
    public void restoreForCancellation(long cancellationId) {
        if (cancellationId <= 0) {
            throw new IllegalArgumentException("취소 원장 ID는 양수여야 합니다.");
        }

        PaymentCancellation cancellation = cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException("취소 원장이 존재하지 않습니다."));

        if (cancellation.getStatus() != PaymentCancellationStatus.COMPLETED) {
            throw new IllegalStateException("완료되지 않은 취소의 쿠폰은 복구할 수 없습니다.");
        }

        orderCouponService.restoreAfterCancellation(cancellation.getOrderRecordId());
    }
}
