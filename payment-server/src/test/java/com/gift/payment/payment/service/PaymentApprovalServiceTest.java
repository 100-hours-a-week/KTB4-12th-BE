package com.gift.payment.payment.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.support.PaymentScenario;
import com.gift.payment.payment.support.PaymentScenarioDelayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentApprovalServiceTest {

    private static final long AMOUNT = 64_000L;
    private static final String REQUEST_HASH = "a".repeat(64);

    private PaymentIdempotencyService idempotencyService;
    private PaymentTransactionService transactionService;
    private PaymentScenarioDelayer scenarioDelayer;
    private PaymentApprovalService service;

    @BeforeEach
    void setUp() {
        idempotencyService = mock(PaymentIdempotencyService.class);
        transactionService = mock(PaymentTransactionService.class);
        scenarioDelayer = mock(PaymentScenarioDelayer.class);

        service = new PaymentApprovalService(
                idempotencyService,
                transactionService,
                scenarioDelayer
        );
    }

    @Test
    @DisplayName("정상 시나리오는 새로운 결제를 승인한다")
    void confirm_approvesPayment() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);
        Payment completed = completedPayment(orderId);

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(
                        PaymentStartResult.created(pending)
                );
        when(transactionService.approve(pending.getId()))
                .thenReturn(completed);

        Payment result = service.confirm(orderId, AMOUNT, PaymentScenario.APPROVE);

        assertThat(result).isSameAs(completed);

        verify(transactionService).approve(pending.getId());
        verify(scenarioDelayer, never()).delay();
    }

    @Test
    @DisplayName("거절 시나리오는 결제를 거절 상태로 확정한다")
    void confirm_declinesPayment() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(
                        PaymentStartResult.created(pending)
                );

        assertBusinessError(
                () -> service.confirm(
                        orderId,
                        AMOUNT,
                        PaymentScenario.DECLINE
                ),
                ErrorCode.PAYMENT_DECLINED
        );

        verify(transactionService).decline(pending.getId());
        verify(transactionService, never()).approve(pending.getId());
    }

    @Test
    @DisplayName("일시 오류 시나리오는 결제를 승인하지 않는다")
    void confirm_returnsTemporaryError() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(
                        PaymentStartResult.created(pending)
                );

        assertBusinessError(
                () -> service.confirm(
                        orderId,
                        AMOUNT,
                        PaymentScenario.TEMPORARY_ERROR
                ),
                ErrorCode.TEMPORARY_PROCESSING_ERROR
        );

        verify(transactionService, never()).approve(pending.getId());
        verify(transactionService, never()).decline(pending.getId());
        verify(scenarioDelayer, never()).delay();
    }

    @Test
    @DisplayName("승인 전 타임아웃은 승인하지 않고 응답만 지연한다")
    void confirm_timesOutBeforeApproval() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(
                        PaymentStartResult.created(pending)
                );

        assertBusinessError(
                () -> service.confirm(
                        orderId,
                        AMOUNT,
                        PaymentScenario.TIMEOUT_BEFORE_APPROVAL
                ),
                ErrorCode.TEMPORARY_PROCESSING_ERROR
        );

        verify(scenarioDelayer).delay();
        verify(transactionService, never()).approve(pending.getId());
        verify(transactionService, never()).decline(pending.getId());
    }

    @Test
    @DisplayName("승인 후 타임아웃은 승인 확정 후 응답을 지연한다")
    void confirm_timesOutAfterApproval() {
        String orderId = UUID.randomUUID().toString();
        Payment pending = payment(orderId);
        Payment completed = completedPayment(orderId);

        when(idempotencyService.start(orderId, AMOUNT))
                .thenReturn(
                        PaymentStartResult.created(pending)
                );
        when(transactionService.approve(pending.getId()))
                .thenReturn(completed);

        Payment result = service.confirm(
                orderId,
                AMOUNT,
                PaymentScenario.TIMEOUT_AFTER_APPROVAL
        );

        assertThat(result).isSameAs(completed);

        InOrder order = inOrder(transactionService, scenarioDelayer);

        order.verify(transactionService).approve(pending.getId());
        order.verify(scenarioDelayer).delay();
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert
                    .ThrowingCallable callable,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode()
                        ).isEqualTo(errorCode)
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

    private Payment completedPayment(String orderId) {
        Payment payment = payment(orderId);

        payment.approve(
                UUID.randomUUID().toString(),
                LocalDateTime.of(
                        2026,
                        10,
                        12,
                        12,
                        0
                )
        );

        return payment;
    }
}
