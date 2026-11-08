package com.gift.gift.domain.notification.service;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.notification.repository.NotificationCommonOutboxRepository;
import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class NotificationCommonOutboxDispatcherTest {
    private final NotificationCommonOutboxRepository repository = mock(NotificationCommonOutboxRepository.class);
    private final NotificationOutboxRepository legacy = mock(NotificationOutboxRepository.class);
    private final NotificationCommonOutboxTransactionService transactions = mock(NotificationCommonOutboxTransactionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationCommonOutboxDispatcher dispatcher =
        new NotificationCommonOutboxDispatcher(repository, legacy, transactions, meters);

    @Test
    @DisplayName("실패 기록 장애가 다음 작업 처리를 막지 않고 관측 지표에 남는다")
    void bookkeepingFailureDoesNotStopRemainingJobs() {
        when(repository.findDueIds()).thenReturn(List.of(1L, 2L));
        when(transactions.process(1L)).thenThrow(new CannotAcquireLockException("busy"));
        doThrow(new DataAccessResourceFailureException("unavailable")).when(transactions)
            .recordFailure(1L, true, CannotAcquireLockException.class.getName());
        when(transactions.process(2L)).thenReturn(NotificationCommonOutboxTransactionService.Outcome.CREATED);

        assertThatCode(dispatcher::dispatch).doesNotThrowAnyException();

        verify(transactions).process(2L);
        assertThat(meters.get("notification.common.outbox.processing")
            .tag("result", "BOOKKEEPING_FAILURE").counter().count()).isEqualTo(1);
        assertThat(meters.get("notification.common.outbox.processing")
            .tag("result", "CREATED").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("잔량 지표는 저장소와 PENDING·FAILED 상태를 구분해 조회한다")
    void backlogGaugesSeparateStorageAndStatus() {
        when(repository.countByStatus("PENDING")).thenReturn(1L);
        when(repository.countByStatus("FAILED")).thenReturn(2L);
        when(legacy.countByStatus("PENDING")).thenReturn(3L);
        when(legacy.countByStatus("FAILED")).thenReturn(4L);
        dispatcher.registerGauges();

        assertThat(meters.get("notification.outbox.jobs").tag("storage", "common")
            .tag("status", "PENDING").gauge().value()).isEqualTo(1);
        assertThat(meters.get("notification.outbox.jobs").tag("storage", "common")
            .tag("status", "FAILED").gauge().value()).isEqualTo(2);
        assertThat(meters.get("notification.outbox.jobs").tag("storage", "legacy")
            .tag("status", "PENDING").gauge().value()).isEqualTo(3);
        assertThat(meters.get("notification.outbox.jobs").tag("storage", "legacy")
            .tag("status", "FAILED").gauge().value()).isEqualTo(4);
    }
}
