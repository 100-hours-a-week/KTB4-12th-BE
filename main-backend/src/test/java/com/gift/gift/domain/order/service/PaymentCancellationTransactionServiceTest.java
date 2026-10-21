package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentCancellationTransactionServiceTest {

    private static final Long CANCELLATION_ID = 10L;

    private PaymentCancellationRepository repository;
    private OrderRepository orderRepository;
    private PaymentCancellationTransactionService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCancellationRepository.class);
        orderRepository = mock(OrderRepository.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-17T03:00:00Z"),
                ZoneOffset.UTC
        );
        service = new PaymentCancellationTransactionService(
                repository,
                orderRepository,
                clock
        );
    }

    @Test
    @DisplayName("취소 결과 미확정 사유를 대기 중인 원장에 기록한다")
    void markResultUnconfirmed_recordsFailure() {
        service.markResultUnconfirmed(
                CANCELLATION_ID,
                PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
        );

        verify(repository).markFailure(
                CANCELLATION_ID,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED,
                LocalDateTime.of(2026, 10, 17, 3, 0)
        );
    }

    @Test
    @DisplayName("로컬 보상 실패 코드는 결과 미확정 기록에 사용할 수 없다")
    void markResultUnconfirmed_rejectsLocalFailure() {
        assertThatThrownBy(() -> service.markResultUnconfirmed(
                CANCELLATION_ID,
                PaymentCancellationFailureCode.LOCAL_COMPENSATION_FAILED
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
