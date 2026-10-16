package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentReconciliationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private OrderRepository orderRepository;
    private PaymentClient paymentClient;
    private OrderApprovalTransactionService approvalService;
    private PaymentCompletionService completionService;
    private PaymentLocalCompletionRetry localCompletionRetry;
    private PaymentReconciliationTransactionService transactionService;
    private PaymentReconciliationService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentClient = mock(PaymentClient.class);
        approvalService = mock(OrderApprovalTransactionService.class);
        completionService = mock(PaymentCompletionService.class);
        localCompletionRetry = mock(PaymentLocalCompletionRetry.class);
        transactionService = mock(PaymentReconciliationTransactionService.class);

        Clock clock = Clock.fixed(
                Instant.parse("2026-10-17T12:00:00Z"),
                ZoneOffset.UTC
        );
        service = new PaymentReconciliationService(
                orderRepository,
                paymentClient,
                approvalService,
                completionService,
                localCompletionRetry,
                transactionService,
                clock,
                100,
                Duration.ofMinutes(1),
                Duration.ofMinutes(10)
        );
    }

    @Test
    @DisplayName("10분 지난 미확정 주문은 최종 조회도 처리 중이면 운영 확인 대상으로 전환한다")
    void reconcile_marksExpiredUnknownOrderAsRequired() {
        Order order = order(PaymentApprovalResult.UNKNOWN, NOW.minusMinutes(10));
        when(orderRepository.findReconciliationCandidates(
                any(), any(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(List.of(order));
        when(paymentClient.getByOrderId(ORDER_ID)).thenReturn(pendingResponse());

        service.reconcilePendingPayments();

        verify(transactionService).markRequired(1L);
        verify(approvalService, never()).markUnknown(any(), any());
    }

    @Test
    @DisplayName("미확정 주문의 승인 완료를 확인하면 승인 기록 후 로컬 처리를 재개한다")
    void reconcile_completesUnknownOrderWhenPaymentIsCompleted() {
        Order order = order(PaymentApprovalResult.UNKNOWN, NOW.minusMinutes(2));
        PaymentResponse response = completedResponse();
        when(orderRepository.findReconciliationCandidates(
                any(), any(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(List.of(order));
        when(paymentClient.getByOrderId(ORDER_ID)).thenReturn(response);

        service.reconcilePendingPayments();

        verify(approvalService).record(
                1L,
                response.paymentKey(),
                response.transactionKey(),
                response.approvedAt()
        );
        verify(localCompletionRetry).execute(eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("승인 완료 주문은 Payment Server 호출 없이 로컬 처리만 재개한다")
    void reconcile_resumesApprovedOrderLocally() {
        Order order = order(PaymentApprovalResult.APPROVED, NOW.minusMinutes(2));
        when(order.getApprovedAt()).thenReturn(NOW.minusMinutes(2));
        when(orderRepository.findReconciliationCandidates(
                any(), any(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(List.of(order));

        service.reconcilePendingPayments();

        verify(localCompletionRetry).execute(eq(ORDER_ID), any());
        verifyNoInteractions(paymentClient);
    }

    private Order order(PaymentApprovalResult result, LocalDateTime createdAt) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(order.getOrderId()).thenReturn(ORDER_ID);
        when(order.getAmount()).thenReturn(64_000L);
        when(order.getApprovalResult()).thenReturn(result);
        when(order.getCreatedAt()).thenReturn(createdAt);
        return order;
    }

    private PaymentResponse pendingResponse() {
        return new PaymentResponse(
                ORDER_ID,
                null,
                null,
                PaymentServerStatus.PENDING,
                64_000L,
                null
        );
    }

    private PaymentResponse completedResponse() {
        return new PaymentResponse(
                ORDER_ID,
                "payment-key",
                "transaction-key",
                PaymentServerStatus.COMPLETED,
                64_000L,
                NOW.minusMinutes(1)
        );
    }
}
