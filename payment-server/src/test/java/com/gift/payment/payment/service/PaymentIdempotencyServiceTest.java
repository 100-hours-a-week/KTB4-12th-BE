package com.gift.payment.payment.service;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.repository.PaymentRepository;
import com.gift.payment.payment.support.PaymentRequestHasher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyServiceTest {

    private static final long AMOUNT = 64_000L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentTransactionService transactionService;

    private PaymentRequestHasher requestHasher;
    private PaymentIdempotencyService service;

    @BeforeEach
    void setUp() {
        requestHasher = new PaymentRequestHasher();

        service = new PaymentIdempotencyService(
                paymentRepository,
                transactionService,
                requestHasher
        );
    }

    @Test
    @DisplayName("기존 결제가 없으면 PENDING 결제를 생성한다")
    void start_createsPayment() {
        String orderId = UUID.randomUUID().toString();
        String requestHash =
                requestHasher.hash(orderId, AMOUNT);
        Payment payment = payment(
                orderId,
                requestHash,
                AMOUNT
        );

        when(paymentRepository.findByOrderId(orderId))
                .thenReturn(Optional.empty());
        when(transactionService.create(
                orderId,
                requestHash,
                AMOUNT
        )).thenReturn(payment);

        PaymentStartResult result = service.start(orderId, AMOUNT);

        assertThat(result.payment()).isSameAs(payment);
        assertThat(result.created()).isTrue();
    }

    @Test
    @DisplayName("같은 주문번호와 금액이면 기존 결제를 반환한다")
    void start_returnsExistingPayment() {
        String orderId = UUID.randomUUID().toString();
        String requestHash = requestHasher.hash(orderId, AMOUNT);
        Payment payment = payment(
                orderId,
                requestHash,
                AMOUNT
        );

        when(paymentRepository.findByOrderId(orderId))
                .thenReturn(Optional.of(payment));

        PaymentStartResult result = service.start(orderId, AMOUNT);

        assertThat(result.payment()).isSameAs(payment);
        assertThat(result.created()).isFalse();

        verify(transactionService, never())
                .create(orderId, requestHash, AMOUNT);
    }

    @Test
    @DisplayName("같은 주문번호에 다른 금액이면 업무 충돌로 처리한다")
    void start_rejectsDifferentAmount() {
        String orderId = UUID.randomUUID().toString();
        String existingHash = requestHasher.hash(orderId, AMOUNT);
        Payment payment = payment(orderId, existingHash, AMOUNT);

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(
                () -> service.start(orderId, AMOUNT + 1)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.errorCode()
                ).isEqualTo(ErrorCode.ORDER_ID_CONFLICT)
        );

        verify(transactionService, never())
                .create(
                        orderId,
                        requestHasher.hash(orderId, AMOUNT + 1),
                        AMOUNT + 1
                );
    }

    @Test
    @DisplayName("주문번호 UNIQUE 경합 시 기존 결제를 반환한다")
    void start_returnsExistingPaymentAfterDuplicate() {
        String orderId = UUID.randomUUID().toString();
        String requestHash = requestHasher.hash(orderId, AMOUNT);
        Payment payment = payment(
                orderId,
                requestHash,
                AMOUNT
        );
        DataIntegrityViolationException duplicate =
                duplicate("uk_payments_order_id");

        when(paymentRepository.findByOrderId(orderId))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(payment)
                );
        when(transactionService.create(orderId, requestHash, AMOUNT))
                .thenThrow(duplicate);

        PaymentStartResult result = service.start(orderId, AMOUNT);

        assertThat(result.payment()).isSameAs(payment);
        assertThat(result.created()).isFalse();
    }

    @Test
    @DisplayName("주문번호 외의 무결성 오류는 그대로 전파한다")
    void start_propagatesOtherIntegrityViolation() {
        String orderId = UUID.randomUUID().toString();
        String requestHash =
                requestHasher.hash(orderId, AMOUNT);
        DataIntegrityViolationException duplicate = duplicate("uk_payments_payment_key");

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(transactionService.create(orderId, requestHash, AMOUNT)).thenThrow(duplicate);

        assertThatThrownBy(
                () -> service.start(orderId, AMOUNT)
        ).isSameAs(duplicate);
    }

    private Payment payment(String orderId, String requestHash, long amount) {
        return new Payment(
                orderId,
                UUID.randomUUID().toString(),
                requestHash,
                amount
        );
    }

    private DataIntegrityViolationException duplicate(String constraint) {
        SQLIntegrityConstraintViolationException cause =
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'payments."
                                + constraint
                                + "'",
                        "23000",
                        1062
                );

        return new DataIntegrityViolationException("결제 UNIQUE 제약 위반", cause);
    }
}
