package com.gift.payment.payment.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCancelTransactionServiceTest {

    private static final long PAYMENT_ID = 1L;
    private static final long CANCELLATION_ID = 2L;
    private static final long AMOUNT = 64_000L;
    private static final String IDEMPOTENCY_KEY = "cancel-request-1";
    private static final String REQUEST_HASH = "a".repeat(64);

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCancelRepository paymentCancelRepository;

    @Mock
    private Payment payment;

    private PaymentCancelTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentCancelTransactionService(
                paymentRepository,
                paymentCancelRepository
        );
    }

    @Test
    @DisplayName("완료 결제를 취소하고 완료된 취소 내역을 저장한다")
    void create_cancelsPaymentAndSavesCancellation() {
        when(paymentRepository.cancel(
                any(Long.class),
                any(PaymentStatus.class),
                any(PaymentStatus.class),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));
        when(payment.getAmount()).thenReturn(AMOUNT);
        when(paymentCancelRepository.saveAndFlush(any(PaymentCancel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentCancel result = service.create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                REQUEST_HASH
        );

        assertThat(result.getStatus())
                .isEqualTo(PaymentCancelStatus.COMPLETED);
        assertThat(result.getCancelAmount()).isEqualTo(AMOUNT);
        assertThat(result.getTransactionKey()).isNotBlank();
        assertThat(result.getCanceledAt()).isNotNull();

        verify(paymentRepository).cancel(
                PAYMENT_ID,
                PaymentStatus.COMPLETED,
                PaymentStatus.CANCELED,
                result.getCanceledAt()
        );
        verify(paymentCancelRepository).saveAndFlush(result);
    }

    @Test
    @DisplayName("결제 상태 조건이 맞지 않으면 취소 내역을 저장하지 않는다")
    void create_rejectsNonCancelablePayment() {
        when(paymentRepository.cancel(
                any(Long.class),
                any(PaymentStatus.class),
                any(PaymentStatus.class),
                any(LocalDateTime.class)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.create(
                PAYMENT_ID,
                IDEMPOTENCY_KEY,
                REQUEST_HASH
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE)
        );

        verify(paymentCancelRepository, never())
                .saveAndFlush(any(PaymentCancel.class));
    }

    @Test
    @DisplayName("처리 중인 취소는 벌크 수정 후 재조회한 엔티티를 완료한다")
    void resume_completesReloadedCancellation() {
        PaymentCancel pending = org.mockito.Mockito.mock(PaymentCancel.class);
        PaymentCancel managed = org.mockito.Mockito.mock(PaymentCancel.class);

        when(pending.getPayment()).thenReturn(payment);
        when(payment.getId()).thenReturn(PAYMENT_ID);
        when(payment.getAmount()).thenReturn(AMOUNT);
        when(paymentCancelRepository.findById(CANCELLATION_ID))
                .thenReturn(
                        Optional.of(pending),
                        Optional.of(managed)
                );
        when(paymentRepository.cancel(
                any(Long.class),
                any(PaymentStatus.class),
                any(PaymentStatus.class),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        PaymentCancel result = service.resume(CANCELLATION_ID);

        assertThat(result).isSameAs(managed);

        ArgumentCaptor<LocalDateTime> canceledAt =
                ArgumentCaptor.forClass(LocalDateTime.class);

        verify(paymentRepository).cancel(
                eq(PAYMENT_ID),
                eq(PaymentStatus.COMPLETED),
                eq(PaymentStatus.CANCELED),
                canceledAt.capture()
        );
        verify(managed).complete(
                any(String.class),
                eq(AMOUNT),
                eq(canceledAt.getValue())
        );
    }
}
