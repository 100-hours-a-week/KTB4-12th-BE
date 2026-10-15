package com.gift.gift.domain.order.service;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.support.PaymentCancellationRequestHasher;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentCancellationServiceTest {

    private static final Long REQUESTER_ID = 1L;
    private static final Long ORDER_RECORD_ID = 10L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PAYMENT_KEY = "7d9f6d7e-a34c-4bbc-97d6-33a2b43c183a";
    private static final String IDEMPOTENCY_KEY = "cancel-request-001";
    private static final String TRANSACTION_KEY = "542f1507-e235-47c2-93c1-a30ed5b31d49";
    private static final long AMOUNT = 64_000L;

    private PaymentCancellationRequestService requestService;
    private PaymentClient paymentClient;
    private PaymentCancellationCompensationService compensationService;
    private PaymentCancellationTransactionService transactionService;
    private PaymentCancellationService service;

    @BeforeEach
    void setUp() {
        requestService = mock(PaymentCancellationRequestService.class);
        paymentClient = mock(PaymentClient.class);
        compensationService = mock(
                PaymentCancellationCompensationService.class
        );
        transactionService = mock(
                PaymentCancellationTransactionService.class
        );
        service = new PaymentCancellationService(
                requestService,
                paymentClient,
                compensationService,
                transactionService
        );
    }

    @Test
    @DisplayName("Payment Server 취소 결과를 보상 처리용 승인 결과로 반환한다")
    void request_returnsCancellationApproval() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        when(requestService.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).thenReturn(PaymentCancellationStartResult.created(cancellation, order));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenReturn(cancelResponse(ORDER_ID, AMOUNT, canceledAt));

        PaymentCancellationApproval result = service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        );

        assertThat(result.cancellation()).isSameAs(cancellation);
        assertThat(result.order()).isSameAs(order);
        assertThat(result.transactionKey()).isEqualTo(TRANSACTION_KEY);
        assertThat(result.cancelAmount()).isEqualTo(AMOUNT);
        assertThat(result.canceledAt()).isEqualTo(canceledAt);
        assertThat(result.created()).isTrue();
        verify(paymentClient).cancel(PAYMENT_KEY, IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("취소 응답 타임아웃은 취소 결과 미확정으로 처리한다")
    void request_mapsTimeoutToUnconfirmed() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();

        when(requestService.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).thenReturn(PaymentCancellationStartResult.created(cancellation, order));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(
                        PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED,
                        "취소 응답 타임아웃",
                        new ResourceAccessException(
                                "응답 타임아웃",
                                new SocketTimeoutException()
                        )
                ));

        assertThatThrownBy(() -> service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.CANCELLATION_RESULT_UNCONFIRMED)
        );
    }

    @Test
    @DisplayName("취소 응답의 주문번호가 다르면 처리 실패로 거부한다")
    void request_rejectsMismatchedOrderId() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        when(requestService.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).thenReturn(PaymentCancellationStartResult.created(cancellation, order));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenReturn(cancelResponse(
                        "550e8400-e29b-41d4-a716-446655440001",
                        AMOUNT,
                        canceledAt
                ));

        assertThatThrownBy(() -> service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR)
        );
    }

    @Test
    @DisplayName("취소 금액이 원결제 금액과 다르면 처리 실패로 거부한다")
    void request_rejectsMismatchedAmount() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        when(requestService.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).thenReturn(PaymentCancellationStartResult.created(cancellation, order));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenReturn(cancelResponse(ORDER_ID, AMOUNT - 1, canceledAt));

        assertThatThrownBy(() -> service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR)
        );
    }

    @Test
    @DisplayName("완료된 로컬 취소 요청은 Payment Server를 다시 호출하지 않는다")
    void request_returnsCompletedCancellation() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        ReflectionTestUtils.setField(
                cancellation,
                "status",
                PaymentCancellationStatus.COMPLETED
        );
        ReflectionTestUtils.setField(
                cancellation,
                "cancelTransactionKey",
                TRANSACTION_KEY
        );
        ReflectionTestUtils.setField(cancellation, "cancelAmount", AMOUNT);
        ReflectionTestUtils.setField(cancellation, "canceledAt", canceledAt);

        when(requestService.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).thenReturn(PaymentCancellationStartResult.existing(cancellation, order));

        PaymentCancellationApproval result = service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        );

        assertThat(result.transactionKey()).isEqualTo(TRANSACTION_KEY);
        assertThat(result.cancelAmount()).isEqualTo(AMOUNT);
        assertThat(result.created()).isFalse();
        verifyNoInteractions(paymentClient);
    }

    @Test
    @DisplayName("거절로 확정된 취소 요청은 기존 거절 결과를 반환한다")
    void request_returnsExistingDecline() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();

        ReflectionTestUtils.setField(
                cancellation,
                "status",
                PaymentCancellationStatus.DECLINED
        );

        when(requestService.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).thenReturn(PaymentCancellationStartResult.existing(cancellation, order));

        assertThatThrownBy(() -> service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.CANCELLATION_DECLINED)
        );

        verifyNoInteractions(paymentClient);
    }

    private Order completedOrder() {
        Order order = new Order(
                ORDER_ID,
                REQUESTER_ID,
                2L,
                3L,
                2,
                32_000L,
                AMOUNT,
                "payment-request-001",
                "a".repeat(64)
        );

        ReflectionTestUtils.setField(order, "id", ORDER_RECORD_ID);
        ReflectionTestUtils.setField(order, "status", OrderStatus.COMPLETED);
        ReflectionTestUtils.setField(order, "paymentKey", PAYMENT_KEY);

        return order;
    }

    private PaymentCancellation pendingCancellation() {
        return new PaymentCancellation(
                ORDER_RECORD_ID,
                REQUESTER_ID,
                CancellationSource.USER,
                IDEMPOTENCY_KEY,
                new PaymentCancellationRequestHasher().hash(ORDER_ID)
        );
    }

    private PaymentCancelResponse cancelResponse(
            String orderId,
            long cancelAmount,
            LocalDateTime canceledAt
    ) {
        return new PaymentCancelResponse(
                orderId,
                PAYMENT_KEY,
                TRANSACTION_KEY,
                PaymentServerStatus.CANCELED,
                cancelAmount,
                canceledAt
        );
    }
}
