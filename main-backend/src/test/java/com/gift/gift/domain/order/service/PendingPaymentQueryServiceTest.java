package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.dto.response.PendingPaymentOrderResponse;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PendingPaymentQueryServiceTest {

    private static final Long PAYER_ID = 1L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private OrderRepository orderRepository;
    private GiftHistoryRepository giftHistoryRepository;
    private PaymentCancellationRepository cancellationRepository;
    private OrderRewardQueryService orderRewardQueryService;
    private PaymentQueryService paymentQueryService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        cancellationRepository = mock(PaymentCancellationRepository.class);
        orderRewardQueryService = mock(OrderRewardQueryService.class);

        paymentQueryService = new PaymentQueryService(
                orderRepository,
                giftHistoryRepository,
                cancellationRepository,
                orderRewardQueryService,
                Clock.fixed(
                        Instant.parse("2026-10-14T05:30:00Z"),
                        ZoneId.of("Asia/Seoul")
                )
        );
    }

    @Test
    @DisplayName("인증 사용자의 PENDING 주문을 최근 생성 순서로 반환한다")
    void getPendingPayments_returnsPendingOrdersForPayer() {
        Order pendingOrder = mock(Order.class);
        when(pendingOrder.getOrderId()).thenReturn(ORDER_ID);
        when(pendingOrder.getStatus()).thenReturn(OrderStatus.PENDING);

        when(orderRepository.findAllByPayerIdAndStatusOrderByCreatedAtDescIdDesc(
                PAYER_ID,
                OrderStatus.PENDING
        )).thenReturn(List.of(pendingOrder));

        List<PendingPaymentOrderResponse> response =
                paymentQueryService.getPendingPayments(PAYER_ID);

        assertThat(response).containsExactly(
                new PendingPaymentOrderResponse(ORDER_ID, OrderStatus.PENDING)
        );

        verify(orderRepository).findAllByPayerIdAndStatusOrderByCreatedAtDescIdDesc(
                PAYER_ID,
                OrderStatus.PENDING
        );
        verifyNoInteractions(
                giftHistoryRepository,
                cancellationRepository,
                orderRewardQueryService
        );
    }

    @Test
    @DisplayName("미확정 주문이 없으면 빈 목록을 반환한다")
    void getPendingPayments_returnsEmptyListWhenNoPendingOrders() {
        when(orderRepository.findAllByPayerIdAndStatusOrderByCreatedAtDescIdDesc(
                PAYER_ID,
                OrderStatus.PENDING
        )).thenReturn(List.of());

        assertThat(paymentQueryService.getPendingPayments(PAYER_ID)).isEmpty();
    }
}
