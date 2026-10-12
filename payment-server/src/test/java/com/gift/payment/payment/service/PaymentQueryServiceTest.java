package com.gift.payment.payment.service;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.repository.PaymentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentQueryServiceTest {

    private static final long AMOUNT = 64_000L;
    private static final String REQUEST_HASH = "a".repeat(64);

    private PaymentRepository paymentRepository;
    private PaymentQueryService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        service = new PaymentQueryService(paymentRepository
        );
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

    private Payment payment(String orderId) {
        return new Payment(
                orderId,
                UUID.randomUUID().toString(),
                REQUEST_HASH,
                AMOUNT
        );
    }
}
