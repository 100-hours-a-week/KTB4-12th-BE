package com.gift.gift.domain.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.infrastructure.payment.client.PaymentClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final Long ORDER_RECORD_ID = 1L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private PaymentRequestService paymentRequestService;
    private PaymentClient paymentClient;
    private OrderApprovalTransactionService approvalService;
    private PaymentCompletionService completionService;
    private PaymentLocalCompletionRetry localCompletionRetry;
    private PaymentAutoCancellationService autoCancellationService;
    private PaymentService paymentService;
    private Order order;
    private PaymentCreateRequest request;

    @BeforeEach
    void setUp() {
        paymentRequestService = mock(PaymentRequestService.class);
        paymentClient = mock(PaymentClient.class);
        approvalService = mock(OrderApprovalTransactionService.class);
        completionService = mock(PaymentCompletionService.class);
        localCompletionRetry = mock(PaymentLocalCompletionRetry.class);
        autoCancellationService = mock(PaymentAutoCancellationService.class);
        paymentService = new PaymentService(
                paymentRequestService,
                paymentClient,
                approvalService,
                completionService,
                localCompletionRetry,
                autoCancellationService
        );

        order = mock(Order.class);
        request = new PaymentCreateRequest(10L, 20L, 1, 32_000L);

        when(paymentRequestService.start(1L, "payment-request", request))
                .thenReturn(PaymentStartResult.existing(order));
        when(order.getId()).thenReturn(ORDER_RECORD_ID);
        when(order.getOrderId()).thenReturn(ORDER_ID);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(order.getApprovalResult()).thenReturn(PaymentApprovalResult.APPROVED);
    }

    @Test
    @DisplayName("승인 후 일시적인 로컬 장애는 실패 상태를 기록하고 처리 중 결과를 반환한다")
    void pay_returnsProcessingForTransientLocalFailure() {
        when(localCompletionRetry.execute(eq(ORDER_ID), any()))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));

        PaymentResult result = paymentService.pay(
                1L,
                "payment-request",
                null,
                request
        );

        assertThat(result.isProcessing()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.processing().orderId()).isEqualTo(ORDER_ID);
        assertThat(result.processing().approvalResult())
                .isEqualTo(PaymentApprovalResult.APPROVED);
        verify(approvalService).markLocalFailure(ORDER_RECORD_ID);
        verifyNoInteractions(paymentClient);
    }

    @Test
    @DisplayName("영구 업무 오류는 자동 보상 취소 후 원래 예외를 반환한다")
    void pay_autoCancelsPermanentBusinessFailure() {
        OrderException failure = new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
        when(localCompletionRetry.execute(eq(ORDER_ID), any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> paymentService.pay(
                1L,
                "payment-request",
                null,
                request
        )).isSameAs(failure);

        verify(approvalService).markLocalFailure(ORDER_RECORD_ID);
        verify(autoCancellationService).cancel(order);
        verifyNoInteractions(paymentClient);
    }
}
