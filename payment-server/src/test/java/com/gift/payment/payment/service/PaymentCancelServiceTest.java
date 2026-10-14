package com.gift.payment.payment.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.entity.PaymentCancelStatus;
import com.gift.payment.payment.entity.PaymentStatus;
import com.gift.payment.payment.repository.PaymentCancelRepository;
import com.gift.payment.payment.repository.PaymentRepository;
import com.gift.payment.payment.support.PaymentCancelRequestHasher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCancelServiceTest {

    private static final long PAYMENT_ID = 1L;
    private static final long AMOUNT = 64_000L;
    private static final String IDEMPOTENCY_KEY = "cancel-request-1";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCancelRepository paymentCancelRepository;

    @Mock
    private PaymentCancelTransactionService transactionService;

    @Mock
    private Payment payment;

    private PaymentCancelRequestHasher requestHasher;
    private PaymentCancelService service;

    @BeforeEach
    void setUp() {
        requestHasher = new PaymentCancelRequestHasher();
        service = new PaymentCancelService(
                paymentRepository,
                paymentCancelRepository,
                transactionService,
                requestHasher
        );
    }

    @Test
    @DisplayName("완료된 결제의 최초 취소 요청은 취소 트랜잭션을 실행한다")
    void cancel_createsCancellation() {
        String paymentKey = paymentKey();
        String orderId = orderId();
        String requestHash = requestHasher.hash(orderId);
        PaymentCancel completed = completedCancellation(requestHash);

        stubPayment(paymentKey, orderId);
        when(payment.getStatus()).thenReturn(PaymentStatus.COMPLETED);
        when(paymentCancelRepository.findByPayment_IdAndIdempotencyKey(
                PAYMENT_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(transactionService.create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                requestHash
        )).thenReturn(completed);

        PaymentCancel result = service.cancel(paymentKey, IDEMPOTENCY_KEY);

        assertThat(result).isSameAs(completed);
        verify(transactionService).create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                requestHash
        );
    }

    @Test
    @DisplayName("동일한 완료 취소 요청은 기존 결과를 반환한다")
    void cancel_returnsCompletedCancellation() {
        String paymentKey = paymentKey();
        String orderId = orderId();
        String requestHash = requestHasher.hash(orderId);
        PaymentCancel completed = completedCancellation(requestHash);

        stubPayment(paymentKey, orderId);
        when(paymentCancelRepository.findByPayment_IdAndIdempotencyKey(
                PAYMENT_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.of(completed));

        PaymentCancel result = service.cancel(paymentKey, IDEMPOTENCY_KEY);

        assertThat(result).isSameAs(completed);
        verify(transactionService, never()).create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                requestHash
        );
    }

    @Test
    @DisplayName("처리 중인 동일 취소 요청은 기존 취소를 재개한다")
    void cancel_resumesPendingCancellation() {
        String paymentKey = paymentKey();
        String orderId = orderId();
        String requestHash = requestHasher.hash(orderId);
        PaymentCancel pending = org.mockito.Mockito.mock(
                PaymentCancel.class
        );
        PaymentCancel completed = completedCancellation(requestHash);

        stubPayment(paymentKey, orderId);
        when(pending.getId()).thenReturn(2L);
        when(pending.getRequestHash()).thenReturn(requestHash);
        when(pending.getStatus())
                .thenReturn(PaymentCancelStatus.PENDING);
        when(paymentCancelRepository.findByPayment_IdAndIdempotencyKey(
                PAYMENT_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.of(pending));
        when(transactionService.resume(pending.getId()))
                .thenReturn(completed);

        PaymentCancel result = service.cancel(paymentKey, IDEMPOTENCY_KEY);

        assertThat(result).isSameAs(completed);
        verify(transactionService).resume(pending.getId());
        verify(transactionService, never()).create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                requestHash
        );
    }

    @Test
    @DisplayName("동시 취소가 먼저 완료되면 같은 멱등 요청의 기존 결과를 반환한다")
    void cancel_recoversConcurrentRequest() {
        String paymentKey = paymentKey();
        String orderId = orderId();
        String requestHash = requestHasher.hash(orderId);
        PaymentCancel completed = completedCancellation(requestHash);

        stubPayment(paymentKey, orderId);
        when(payment.getStatus()).thenReturn(PaymentStatus.COMPLETED);
        when(paymentCancelRepository.findByPayment_IdAndIdempotencyKey(
                PAYMENT_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                Optional.empty(),
                Optional.of(completed)
        );
        when(transactionService.create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                requestHash
        )).thenThrow(new BusinessException(
                ErrorCode.PAYMENT_NOT_CANCELABLE
        ));

        PaymentCancel result = service.cancel(paymentKey, IDEMPOTENCY_KEY);

        assertThat(result).isSameAs(completed);
    }

    @Test
    @DisplayName("동시 요청의 취소 내역이 없으면 최초 취소 불가 오류를 유지한다")
    void cancel_keepsOriginalErrorWhenConcurrentRequestIsMissing() {
        String paymentKey = paymentKey();
        String orderId = orderId();
        String requestHash = requestHasher.hash(orderId);

        stubPayment(paymentKey, orderId);
        when(payment.getStatus()).thenReturn(PaymentStatus.COMPLETED);
        when(paymentCancelRepository.findByPayment_IdAndIdempotencyKey(
                PAYMENT_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(transactionService.create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                requestHash
        )).thenThrow(new BusinessException(
                ErrorCode.PAYMENT_NOT_CANCELABLE
        ));

        assertBusinessError(
                () -> service.cancel(paymentKey, IDEMPOTENCY_KEY),
                ErrorCode.PAYMENT_NOT_CANCELABLE
        );
    }

    @Test
    @DisplayName("결제를 찾을 수 없으면 취소 트랜잭션을 실행하지 않는다")
    void cancel_rejectsMissingPayment() {
        String paymentKey = paymentKey();

        when(paymentRepository.findByPaymentKey(paymentKey))
                .thenReturn(Optional.empty());

        assertBusinessError(
                () -> service.cancel(paymentKey, IDEMPOTENCY_KEY),
                ErrorCode.PAYMENT_NOT_FOUND
        );

        verifyNoInteractions(transactionService);
    }

    private void stubPayment(
            String paymentKey,
            String orderId
    ) {
        when(paymentRepository.findByPaymentKey(paymentKey))
                .thenReturn(Optional.of(payment));
        when(payment.getId()).thenReturn(PAYMENT_ID);
        when(payment.getOrderId()).thenReturn(orderId);
    }

    private PaymentCancel completedCancellation(String requestHash) {
        PaymentCancel cancellation = new PaymentCancel(
                payment,
                IDEMPOTENCY_KEY,
                requestHash
        );
        cancellation.complete(
                UUID.randomUUID().toString(),
                AMOUNT,
                LocalDateTime.of(2026, 10, 14, 12, 0)
        );
        return cancellation;
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(errorCode)
                );
    }

    private String paymentKey() {
        return UUID.randomUUID().toString();
    }

    private String orderId() {
        return UUID.randomUUID().toString();
    }
}
