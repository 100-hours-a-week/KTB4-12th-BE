package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskService;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentCancellationTransactionServiceTest {

    private static final Long CANCELLATION_ID = 10L;
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 10, 17, 3, 0);

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
                mock(GiftHistoryRepository.class),
                clock,
                mock(PaymentRecoveryTaskService.class)
        );
    }

    @Test
    @DisplayName("취소 미확정 결과와 첫 복구 조회 시각을 저장한다")
    void markResultUnconfirmed_recordsFailureAndFirstRetry() {
        service.markResultUnconfirmed(
                CANCELLATION_ID,
                PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
        );

        verify(repository).markUnconfirmed(
                CANCELLATION_ID,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED,
                NOW,
                NOW.plusSeconds(5)
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
