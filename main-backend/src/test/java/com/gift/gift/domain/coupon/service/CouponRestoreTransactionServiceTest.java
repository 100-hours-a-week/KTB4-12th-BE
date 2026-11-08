package com.gift.gift.domain.coupon.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponRestoreTransactionServiceTest {

    private static final long CANCELLATION_ID = 101L;
    private static final long ORDER_RECORD_ID = 202L;

    @Mock
    private PaymentCancellationRepository cancellationRepository;

    @Mock
    private OrderCouponService orderCouponService;

    @Mock
    private PaymentCancellation cancellation;

    private CouponRestoreTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new CouponRestoreTransactionService(
                cancellationRepository,
                orderCouponService
        );
    }

    @Test
    @DisplayName("완료된 취소 원장의 주문 ID로 사용 쿠폰 복구를 호출한다")
    void restoreForCancellation_completedCancellation_restoresCouponForOrder() {
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(Optional.of(cancellation));
        when(cancellation.getStatus()).thenReturn(PaymentCancellationStatus.COMPLETED);
        when(cancellation.getOrderRecordId()).thenReturn(ORDER_RECORD_ID);

        transactionService.restoreForCancellation(CANCELLATION_ID);

        verify(orderCouponService).restoreAfterCancellation(ORDER_RECORD_ID);
    }

    @Test
    @DisplayName("완료되지 않은 취소 원장은 쿠폰 복구를 실행하지 않는다")
    void restoreForCancellation_pendingCancellation_throws() {
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(Optional.of(cancellation));
        when(cancellation.getStatus()).thenReturn(PaymentCancellationStatus.PENDING);

        assertThatThrownBy(() -> transactionService.restoreForCancellation(CANCELLATION_ID))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(orderCouponService);
    }

    @Test
    @DisplayName("취소 원장이 없으면 쿠폰 복구를 실행하지 않는다")
    void restoreForCancellation_missingCancellation_throws() {
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.restoreForCancellation(CANCELLATION_ID))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(orderCouponService);
    }
}
