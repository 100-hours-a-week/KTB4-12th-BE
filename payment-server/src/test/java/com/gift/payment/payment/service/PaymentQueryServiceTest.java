package com.gift.payment.payment.service;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.repository.PaymentCancelRepository;
import com.gift.payment.payment.repository.PaymentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentQueryServiceTest {

    private static final long AMOUNT = 64_000L;
    private static final String REQUEST_HASH = "a".repeat(64);

    private PaymentRepository paymentRepository;
    private PaymentCancelRepository paymentCancelRepository;
    private PaymentQueryService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentCancelRepository = mock(PaymentCancelRepository.class);
        service = new PaymentQueryService(paymentRepository, paymentCancelRepository);
    }

    @Test
    @DisplayName("주문번호로 Payment Server의 원본 결제를 조회한다")
    void getByOrderId_returnsPayment() {
        String orderId = UUID.randomUUID().toString();
        Payment payment = payment(orderId);

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        Payment result = service.getByOrderId(orderId);

        assertThat(result).isSameAs(payment);
    }

    @Test
    @DisplayName("주문번호에 해당하는 결제가 없으면 조회 오류를 반환한다")
    void getByOrderId_throwsWhenPaymentIsMissing() {
        String orderId = UUID.randomUUID().toString();

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.getByOrderId(orderId)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.errorCode()
                ).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND)
        );
    }

    @Test
    @DisplayName("결제 키와 멱등성 키로 기존 취소 원장을 조회한다")
    void getCancellation_returnsCancellation() {
        String paymentKey = UUID.randomUUID().toString();

        String idempotencyKey = "cancel-request-001";

        PaymentCancel cancellation = mock(PaymentCancel.class);

        when(paymentCancelRepository
                .findByPayment_PaymentKeyAndIdempotencyKey(paymentKey, idempotencyKey))
                .thenReturn(Optional.of(cancellation));

        PaymentCancel result = service.getCancellation(paymentKey, idempotencyKey);

        assertThat(result).isSameAs(cancellation);
    }

    @Test
    @DisplayName("해당 멱등 취소 원장이 없으면 조회 오류를 반환한다")
    void getCancellation_throwsWhenCancellationIsMissing() {
        String paymentKey = UUID.randomUUID().toString();

        String idempotencyKey = "cancel-request-001";

        when(paymentCancelRepository
                .findByPayment_PaymentKeyAndIdempotencyKey(paymentKey, idempotencyKey))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.getCancellation(paymentKey, idempotencyKey)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND)
        );
    }

    private Payment payment(String orderId) {
        return new Payment(orderId, UUID.randomUUID().toString(), REQUEST_HASH, AMOUNT);
    }
}
