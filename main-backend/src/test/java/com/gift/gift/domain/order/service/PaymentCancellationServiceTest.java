package com.gift.gift.domain.order.service;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
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
        when(transactionService.claimUserRequest(any())).thenReturn(true);
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
    @DisplayName("취소 응답 유실 후 조회 결과가 완료이면 취소를 재개한다")
    void request_recoversCancellationAfterLostResponse() {
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
                .thenThrow(new PaymentClientException(
                        PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED,
                        "취소 응답 타임아웃",
                        (String) null
                ));
        when(paymentClient.getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenReturn(cancelResponse(ORDER_ID, AMOUNT, canceledAt));

        PaymentCancellationApproval result = service.request(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        );

        assertThat(result.transactionKey()).isEqualTo(TRANSACTION_KEY);
        assertThat(result.cancelAmount()).isEqualTo(AMOUNT);
        assertThat(result.canceledAt()).isEqualTo(canceledAt);

        verify(paymentClient).getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY);
        verify(transactionService, never()).markDeclined(any());
    }

    @Test
    @DisplayName("취소 명령과 최종 조회가 모두 미확정이면 원장에 기록한다")
    void request_marksUnconfirmedWhenFinalQueryFails() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();
        ReflectionTestUtils.setField(cancellation, "id", 20L);

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
                        (String) null
                ));
        when(paymentClient.getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(
                        PaymentClientFailure.PAYMENT_NOT_FOUND,
                        "취소 결과 없음",
                        (String) null
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

        verify(transactionService).markResultUnconfirmed(
                20L,
                PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
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

    @Test
    @DisplayName("외부 취소 거절 확정은 원장에 기록해 수락 차단을 해제한다")
    void request_recordsDefinitiveCancellationDecline() {
        Order order = completedOrder();
        PaymentCancellation cancellation = pendingCancellation();
        ReflectionTestUtils.setField(cancellation, "id", 20L);
        when(requestService.start(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER))
                .thenReturn(PaymentCancellationStartResult.created(cancellation, order));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(PaymentClientFailure.CANCELLATION_DECLINED,
                        "취소 거절", (String) null));
        assertThatThrownBy(() -> service.request(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER))
                .isInstanceOf(OrderException.class);
        verify(transactionService).markDeclined(20L);
        verifyNoInteractions(compensationService);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class,
            names = {"INVALID_REQUEST", "AUTHENTICATION_FAILED", "CANCELLATION_DECLINED"})
    @DisplayName("앞선 취소 미확정 뒤 거절을 받아도 실제 취소 완료를 조회해 반환한다")
    void request_recoversCancellationAfterRetryRejection(PaymentClientFailure failure) {
        PaymentCancellation cancellation = pendingCancellation();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 10, 26, 12, 0);
        when(requestService.start(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER))
                .thenReturn(PaymentCancellationStartResult.created(cancellation, completedOrder()));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(failure, "재시도 거절", "trace").withUncertainOutcome());
        when(paymentClient.getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenReturn(cancelResponse(ORDER_ID, AMOUNT, canceledAt));

        PaymentCancellationApproval result = service.request(
                REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER
        );

        assertThat(result.transactionKey()).isEqualTo(TRANSACTION_KEY);
        assertThat(result.canceledAt()).isEqualTo(canceledAt);
        verify(paymentClient).getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY);
        verify(transactionService, never()).markDeclined(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class,
            names = {"INVALID_REQUEST", "AUTHENTICATION_FAILED", "CANCELLATION_DECLINED"})
    @DisplayName("취소 재시도 거절 후 조회도 실패하면 취소를 종료하거나 보상하지 않는다")
    void request_retryRejectionKeepsCancellationUnconfirmed(PaymentClientFailure failure) {
        PaymentCancellation cancellation = pendingCancellation();
        ReflectionTestUtils.setField(cancellation, "id", 20L);
        when(requestService.start(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER))
                .thenReturn(PaymentCancellationStartResult.created(cancellation, completedOrder()));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(failure, "재시도 거절", "trace").withUncertainOutcome());
        when(paymentClient.getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(PaymentClientFailure.PAYMENT_NOT_FOUND,
                        "취소 결과 없음", (String) null));

        assertThatThrownBy(() -> service.request(
                REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER
        )).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CANCELLATION_RESULT_UNCONFIRMED));

        verify(transactionService).markResultUnconfirmed(
                20L, PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
        );
        verify(transactionService, never()).markDeclined(any());
        verifyNoInteractions(compensationService);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class, names = {"AUTHENTICATION_FAILED", "INVALID_REQUEST"})
    @DisplayName("최초 사용자 취소 입력·인증 거절은 취소 기록만 종료한다")
    void request_declinesFirstUserAuthenticationRejection(PaymentClientFailure failure) {
        PaymentCancellation cancellation = pendingCancellation();
        ReflectionTestUtils.setField(cancellation, "id", 20L);
        when(requestService.start(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER))
                .thenReturn(PaymentCancellationStartResult.created(cancellation, completedOrder()));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(failure,
                        "내부 인증 실패", (String) null));

        assertThatThrownBy(() -> service.cancel(
                REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER
        )).isInstanceOf(OrderException.class);

        verify(transactionService).markDeclined(20L);
        verifyNoInteractions(compensationService);
    }

    @ParameterizedTest
    @EnumSource(value = CancellationSource.class,
            names = {"GIFT_EXPIRATION", "LOCAL_PROCESSING_FAILURE"})
    @DisplayName("만료·자동 보상 취소의 인증 실패는 종료하지 않고 동일 키 재시도를 허용한다")
    void request_automaticAuthenticationFailureCanRetry(CancellationSource source) {
        PaymentCancellation cancellation = new PaymentCancellation(
                ORDER_RECORD_ID, REQUESTER_ID, source, IDEMPOTENCY_KEY,
                new PaymentCancellationRequestHasher().hash(ORDER_ID)
        );
        when(requestService.start(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, source))
                .thenReturn(PaymentCancellationStartResult.existing(cancellation, completedOrder()));
        when(paymentClient.cancel(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenThrow(new PaymentClientException(PaymentClientFailure.AUTHENTICATION_FAILED,
                        "내부 인증 실패", (String) null))
                .thenReturn(cancelResponse(ORDER_ID, AMOUNT, LocalDateTime.of(2026, 10, 26, 12, 0)));

        assertThatThrownBy(() -> service.request(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, source))
                .isInstanceOf(OrderException.class);
        assertThat(service.request(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, source).transactionKey())
                .isEqualTo(TRANSACTION_KEY);
        verify(transactionService, never()).claimUserRequest(any());
        verify(transactionService, never()).markDeclined(any());
        verify(paymentClient, times(2)).cancel(PAYMENT_KEY, IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("사용자 취소 선점 실패는 명령을 재전송하지 않고 결과만 조회한다")
    void request_claimLoserOnlyQueriesCancellation() {
        PaymentCancellation cancellation = pendingCancellation();
        when(transactionService.claimUserRequest(any())).thenReturn(false);
        when(requestService.start(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER))
                .thenReturn(PaymentCancellationStartResult.existing(cancellation, completedOrder()));
        when(paymentClient.getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY))
                .thenReturn(cancelResponse(ORDER_ID, AMOUNT, LocalDateTime.of(2026, 10, 26, 12, 0)));

        assertThat(service.request(REQUESTER_ID, ORDER_ID, IDEMPOTENCY_KEY, CancellationSource.USER)
                .transactionKey()).isEqualTo(TRANSACTION_KEY);
        verify(paymentClient, never()).cancel(any(), any());
        verify(paymentClient).getCancellation(PAYMENT_KEY, IDEMPOTENCY_KEY);
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
