package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentAutoCancellationTransactionServiceTest {

    private static final LocalDateTime CANCELED_AT =
            LocalDateTime.of(2026, 10, 17, 12, 0);

    private OrderRepository orderRepository;
    private PaymentCancellationRepository cancellationRepository;
    private PaymentAutoCancellationTransactionService service;
    private OrderCouponService orderCouponService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        cancellationRepository = mock(PaymentCancellationRepository.class);
        orderCouponService = mock(OrderCouponService.class);
        service = new PaymentAutoCancellationTransactionService(
                orderRepository,
                cancellationRepository,
                orderCouponService,
                Clock.fixed(
                        Instant.parse("2026-10-17T12:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    @DisplayName("자동 보상 취소는 주문·취소 원장과 쿠폰 복구를 함께 처리한다")
    void complete_updatesOrderCancellationAndRestoresCoupon() {
        Order order = mock(Order.class);
        PaymentCancellation cancellation = mock(PaymentCancellation.class);
        PaymentCancellationApproval approval = new PaymentCancellationApproval(
                cancellation,
                order,
                "cancel-transaction-key",
                64_000L,
                CANCELED_AT,
                true
        );

        when(cancellation.getId()).thenReturn(10L);
        when(cancellation.getOrderRecordId()).thenReturn(1L);
        when(cancellation.getCancellationSource())
                .thenReturn(CancellationSource.LOCAL_PROCESSING_FAILURE);
        when(cancellation.getStatus())
                .thenReturn(PaymentCancellationStatus.PENDING);
        when(order.getId()).thenReturn(1L);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(order.getApprovalResult()).thenReturn(PaymentApprovalResult.APPROVED);
        when(order.getAmount()).thenReturn(64_000L);
        when(cancellationRepository.findById(10L))
                .thenReturn(Optional.of(cancellation));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.cancelApproved(
                1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELED,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.LOCAL_PROCESSING_FAILED,
                CANCELED_AT
        )).thenReturn(1);
        when(cancellationRepository.complete(
                10L,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationStatus.COMPLETED,
                "cancel-transaction-key",
                64_000L,
                CANCELED_AT
        )).thenReturn(1);

        service.complete(approval);
        verify(orderCouponService).restoreAfterFailure(1L);

        verify(orderRepository).cancelApproved(
                1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELED,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.LOCAL_PROCESSING_FAILED,
                CANCELED_AT
        );
        verify(cancellationRepository).complete(
                10L,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationStatus.COMPLETED,
                "cancel-transaction-key",
                64_000L,
                CANCELED_AT
        );
    }
}
