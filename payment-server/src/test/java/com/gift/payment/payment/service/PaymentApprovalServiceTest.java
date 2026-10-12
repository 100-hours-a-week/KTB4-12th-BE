package com.gift.payment.payment.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.support.PaymentScenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentApprovalServiceTest {

    private static final long AMOUNT = 64_000L;
    private static final String REQUEST_HASH = "a".repeat(64);

    private PaymentIdempotencyService idempotencyService;
    private PaymentTransactionService transactionService;
    private PaymentApprovalService service;

    @BeforeEach
    void setUp() {
        idempotencyService = mock(PaymentIdempotencyService.class);
        transactionService = mock(PaymentTransactionService.class);
        service = new PaymentApprovalService(idempotencyService, transactionService);
    }

    @Test
    @DisplayName("새로운 결제 요청은 한 번만 승인한다")
    void confirm_approvesNewPayment() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);
        Payment completed = payment(orderId);

        completed.approve(UUID.randomUUID().toString(), LocalDateTime.now());

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(PaymentStartResult.created(pending));
        when(transactionService.approve(pending.getId()))
                .thenReturn(completed);

        Payment result = service.confirm(orderId, AMOUNT, PaymentScenario.APPROVE);

        assertThat(result).isSameAs(completed);

        verify(transactionService).approve(pending.getId());
    }

    @Test
    @DisplayName("거절 시 결제를 거절 상태로 확정하고 오류를 반환한다")
    void confirm_declinesPayment() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(
                        PaymentStartResult.created(pending)
                );

        assertThatThrownBy(() -> service.confirm(
                orderId,
                AMOUNT,
                PaymentScenario.DECLINE
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.errorCode()
                ).isEqualTo(ErrorCode.PAYMENT_DECLINED)
        );

        verify(transactionService)
                .decline(pending.getId());
        verify(transactionService, never())
                .approve(pending.getId());
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
