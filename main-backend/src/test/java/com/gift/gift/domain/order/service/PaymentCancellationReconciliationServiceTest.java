package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerCancellationStatus;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCancellationReconciliationServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 10, 17, 12, 0);
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PAYMENT_KEY = "payment-key";
    private static final Long CANCELLATION_ID = 12L;

    private PaymentCancellationRepository cancellationRepository;
    private OrderRepository orderRepository;
    private PaymentClient paymentClient;
    private PaymentCancellationTransactionService transactionService;
    private PaymentCancellationCompensationService compensationService;
    private PaymentCancellationReconciliationService service;
    private PaymentCancellation cancellation;
    private Order order;

    @BeforeEach
    void setUp() {
        cancellationRepository = mock(PaymentCancellationRepository.class);
        orderRepository = mock(OrderRepository.class);
        paymentClient = mock(PaymentClient.class);
        transactionService = mock(PaymentCancellationTransactionService.class);
        compensationService = mock(PaymentCancellationCompensationService.class);

        Clock clock = Clock.fixed(
                Instant.parse("2026-10-17T12:00:00Z"),
                ZoneOffset.UTC
        );

        service = new PaymentCancellationReconciliationService(
                cancellationRepository,
                orderRepository,
                paymentClient,
                transactionService,
                compensationService,
                clock,
                100,
                Duration.ofMinutes(10)
        );

        cancellation = mock(PaymentCancellation.class);
        order = mock(Order.class);

        when(cancellation.getId()).thenReturn(CANCELLATION_ID);
        when(cancellation.getOrderRecordId()).thenReturn(1L);
        when(cancellation.getIdempotencyKey()).thenReturn("cancel-key");
        when(cancellation.getStatus()).thenReturn(PaymentCancellationStatus.PENDING);
        when(cancellation.getCancellationSource()).thenReturn(CancellationSource.USER);
        when(cancellation.getReconciliationStartedAt()).thenReturn(NOW.minusMinutes(1));
        when(cancellation.getReconciliationAttempts()).thenReturn(0);
        when(cancellation.getCancelTransactionKey()).thenReturn(null);
        when(cancellation.getCancelAmount()).thenReturn(null);
        when(cancellation.getCanceledAt()).thenReturn(null);

        when(order.getId()).thenReturn(1L);
        when(order.getOrderId()).thenReturn(ORDER_ID);
        when(order.getPaymentKey()).thenReturn(PAYMENT_KEY);
        when(order.getAmount()).thenReturn(64_000L);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(cancellationRepository.findDueReconciliationCandidates(
                eq(CancellationSource.USER),
                eq(PaymentCancellationStatus.PENDING),
                eq(PaymentCancellationFailureCode.CANCELLATION_RECONCILIATION_REQUIRED),
                eq(NOW),
                any(Pageable.class)
        )).thenReturn(List.of(cancellation));
    }

    @Test
    @DisplayName("취소 원장이 PENDING이면 GET 확인 후 같은 키로만 POST한다")
    void reconcile_pendingCancellationGetsBeforeRetryPost() {
        when(paymentClient.getCancellation(PAYMENT_KEY, "cancel-key"))
                .thenReturn(pendingCancellationResponse());
        when(transactionService.reserveRecoveryCancelPost(CANCELLATION_ID, 3))
                .thenReturn(true);
        when(paymentClient.cancel(PAYMENT_KEY, "cancel-key"))
                .thenReturn(completedCancellationResponse());
        PaymentCancellation savedCancellation = mock(PaymentCancellation.class);
        when(savedCancellation.getCancelTransactionKey())
                .thenReturn("cancel-transaction");
        when(savedCancellation.getCancelAmount()).thenReturn(64_000L);
        when(savedCancellation.getCanceledAt()).thenReturn(NOW.minusSeconds(30));
        when(cancellationRepository.findById(CANCELLATION_ID))
                .thenReturn(Optional.of(savedCancellation));

        service.reconcilePendingCancellations();

        var order = inOrder(paymentClient, transactionService, compensationService);
        order.verify(paymentClient).getCancellation(PAYMENT_KEY, "cancel-key");
        order.verify(transactionService).reserveRecoveryCancelPost(CANCELLATION_ID, 3);
        order.verify(paymentClient).cancel(PAYMENT_KEY, "cancel-key");
        order.verify(transactionService).recordPaymentServerCancellationResult(
                CANCELLATION_ID,
                "cancel-transaction",
                64_000L,
                NOW.minusSeconds(30)
        );
        order.verify(compensationService).compensate(any(PaymentCancellationApproval.class));
    }

    @Test
    @DisplayName("취소 조회 404 뒤 원결제가 COMPLETED일 때만 같은 키로 POST한다")
    void reconcile_missingCancellationPostsOnlyWhenOriginalPaymentIsCompleted() {
        when(paymentClient.getCancellation(PAYMENT_KEY, "cancel-key"))
                .thenThrow(new com.gift.gift.infrastructure.payment.exception.PaymentClientException(
                        com.gift.gift.infrastructure.payment.exception.PaymentClientFailure.PAYMENT_NOT_FOUND,
                        "취소 원장을 찾을 수 없습니다.",
                        (String) null
                ));
        when(paymentClient.getByOrderId(ORDER_ID)).thenReturn(
                new PaymentResponse(
                        ORDER_ID,
                        PAYMENT_KEY,
                        "approval-transaction",
                        PaymentServerStatus.COMPLETED,
                        64_000L,
                        NOW.minusMinutes(2)
                )
        );
        when(transactionService.reserveRecoveryCancelPost(CANCELLATION_ID, 3))
                .thenReturn(true);
        when(paymentClient.cancel(PAYMENT_KEY, "cancel-key"))
                .thenReturn(completedCancellationResponse());

        service.reconcilePendingCancellations();

        verify(paymentClient).getCancellation(PAYMENT_KEY, "cancel-key");
        verify(paymentClient).getByOrderId(ORDER_ID);
        verify(paymentClient).cancel(PAYMENT_KEY, "cancel-key");
    }

    @Test
    @DisplayName("취소 복구 기한이 지나면 마지막 GET 뒤 운영 확인 상태로 전환한다")
    void reconcile_expiredCancellationDoesNotPost() {
        when(cancellation.getReconciliationStartedAt()).thenReturn(NOW.minusMinutes(10));
        when(paymentClient.getCancellation(PAYMENT_KEY, "cancel-key"))
                .thenReturn(pendingCancellationResponse());

        service.reconcilePendingCancellations();

        verify(paymentClient).getCancellation(PAYMENT_KEY, "cancel-key");
        verify(paymentClient, never()).cancel(any(), any());
        verify(transactionService).markReconciliationRequired(CANCELLATION_ID);
    }

    @Test
    @DisplayName("조회 응답의 주문 식별 정보가 다르면 POST하지 않고 운영 확인으로 보낸다")
    void reconcile_identityMismatchRequiresReview() {
        when(paymentClient.getCancellation(PAYMENT_KEY, "cancel-key"))
                .thenReturn(new PaymentCancelResponse(
                        "another-order",
                        PAYMENT_KEY,
                        null,
                        PaymentServerStatus.COMPLETED,
                        null,
                        null,
                        PaymentServerCancellationStatus.PENDING,
                        null
                ));

        service.reconcilePendingCancellations();

        verify(paymentClient, never()).cancel(any(), any());
        verify(transactionService).markReconciliationRequired(CANCELLATION_ID);
    }

    private PaymentCancelResponse pendingCancellationResponse() {
        return new PaymentCancelResponse(
                ORDER_ID,
                PAYMENT_KEY,
                null,
                PaymentServerStatus.COMPLETED,
                null,
                null,
                PaymentServerCancellationStatus.PENDING,
                null
        );
    }

    private PaymentCancelResponse completedCancellationResponse() {
        return new PaymentCancelResponse(
                ORDER_ID,
                PAYMENT_KEY,
                "cancel-transaction",
                PaymentServerStatus.CANCELED,
                64_000L,
                NOW.minusSeconds(30),
                PaymentServerCancellationStatus.COMPLETED,
                null
        );
    }
}
