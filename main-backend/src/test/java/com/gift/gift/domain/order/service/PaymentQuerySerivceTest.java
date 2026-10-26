package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.dto.response.PaymentStatusResponse;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentQueryServiceTest {

    private static final Long PAYER_ID = 1L;
    private static final Long ORDER_RECORD_ID = 10L;
    private static final Long GIFT_ID = 501L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final long AMOUNT = 64_000L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 14, 14, 30);

    private OrderRepository orderRepository;
    private GiftHistoryRepository giftHistoryRepository;
    private PaymentQueryService paymentQueryService;
    private OrderRewardQueryService orderRewardQueryService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        orderRewardQueryService = mock(OrderRewardQueryService.class);
        when(orderRewardQueryService.summarize(any(), any()))
                .thenReturn(new OrderRewardSummary(null, null, null, null));

        Clock clock = Clock.fixed(Instant.parse("2026-10-14T05:30:00Z"), ZoneId.of("Asia/Seoul"));

        paymentQueryService = new PaymentQueryService(
                orderRepository,
                giftHistoryRepository,
                orderRewardQueryService,
                clock
        );
    }

    @Test
    @DisplayName("인증 사용자의 완료된 결제와 연결 선물 상태를 조회한다")
    void getStatus_returnsOwnedPayment() {
        Order order = mock(Order.class);
        GiftHistory gift = mock(GiftHistory.class);

        when(order.getId()).thenReturn(ORDER_RECORD_ID);
        when(order.getOrderId()).thenReturn(ORDER_ID);
        when(order.getStatus()).thenReturn(OrderStatus.COMPLETED);
        when(order.getApprovalResult()).thenReturn(PaymentApprovalResult.APPROVED);
        when(order.getFailureCode()).thenReturn(OrderFailureCode.LOCAL_PROCESSING_FAILED);
        when(order.getAmount()).thenReturn(AMOUNT);
        when(order.getApprovedAt()).thenReturn(APPROVED_AT);
        when(order.getCanceledAt()).thenReturn(null);

        when(gift.getId()).thenReturn(GIFT_ID);
        when(gift.getStatus()).thenReturn(GiftStatus.WAITING_FOR_ACCEPTANCE);

        when(orderRepository.findByOrderIdAndPayerId(ORDER_ID, PAYER_ID)).thenReturn(Optional.of(order));

        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID)).thenReturn(Optional.of(gift));

        PaymentStatusResponse response =paymentQueryService.getStatus(PAYER_ID, ORDER_ID);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.paymentStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(response.approvalResult()).isEqualTo(PaymentApprovalResult.APPROVED);
        assertThat(response.failureCode()).isEqualTo(OrderFailureCode.LOCAL_PROCESSING_FAILED);
        assertThat(response.giftId()).isEqualTo(GIFT_ID);
        assertThat(response.giftStatus()).isEqualTo(GiftStatus.WAITING_FOR_ACCEPTANCE);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        assertThat(response.approvedAt()).isEqualTo("2026-10-14T14:30+09:00");
        assertThat(response.canceledAt()).isNull();

        verify(orderRepository).findByOrderIdAndPayerId(ORDER_ID, PAYER_ID);
        verify(giftHistoryRepository).findByOrderRecordId(ORDER_RECORD_ID);
        verifyNoMoreInteractions(orderRepository, giftHistoryRepository);
    }

    @Test
    @DisplayName("결제 완료 전에는 선물과 승인 시각을 null로 반환한다")
    void getStatus_returnsNullableFields_forPendingPayment() {
        Order order = mock(Order.class);

        when(order.getId()).thenReturn(ORDER_RECORD_ID);
        when(order.getOrderId()).thenReturn(ORDER_ID);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(order.getApprovalResult()).thenReturn(null);
        when(order.getAmount()).thenReturn(AMOUNT);
        when(order.getApprovedAt()).thenReturn(null);
        when(order.getCanceledAt()).thenReturn(null);

        when(orderRepository.findByOrderIdAndPayerId(
                ORDER_ID,
                PAYER_ID
        )).thenReturn(Optional.of(order));

        when(giftHistoryRepository.findByOrderRecordId(
                ORDER_RECORD_ID
        )).thenReturn(Optional.empty());

        PaymentStatusResponse response =
                paymentQueryService.getStatus(
                        PAYER_ID,
                        ORDER_ID
                );

        assertThat(response.paymentStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(response.approvalResult()).isNull();
        assertThat(response.giftId()).isNull();
        assertThat(response.giftStatus()).isNull();
        assertThat(response.approvedAt()).isNull();
        assertThat(response.canceledAt()).isNull();
    }

    @Test
    @DisplayName("존재하지 않거나 소유하지 않은 결제는 찾을 수 없음으로 처리한다")
    void getStatus_fails_whenPaymentIsNotOwned() {
        when(orderRepository.findByOrderIdAndPayerId(
                ORDER_ID,
                PAYER_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                paymentQueryService.getStatus(PAYER_ID, ORDER_ID)
        ).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND)
        );

        verify(orderRepository).findByOrderIdAndPayerId(ORDER_ID, PAYER_ID);
        verifyNoInteractions(giftHistoryRepository);
    }
}
