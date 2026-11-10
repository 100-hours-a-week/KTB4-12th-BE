package com.gift.gift.domain.notification.service;

import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.notification.repository.NotificationCommonOutboxRepository;
import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;
import com.gift.gift.domain.notification.support.NotificationPersistenceFailures;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCommonOutboxDispatcher {
    private final NotificationCommonOutboxRepository repository;
    private final NotificationOutboxRepository legacy;
    private final NotificationCommonOutboxTransactionService transactions;
    private final MeterRegistry metrics;

    @PostConstruct
    void registerGauges() {
        for (String state : new String[]{"PENDING", "FAILED"}) {
            metrics.gauge("notification.outbox.jobs", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("storage", "common"),
                io.micrometer.core.instrument.Tag.of("status", state)), repository, r -> r.countByStatus(state));
            metrics.gauge("notification.outbox.jobs", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("storage", "legacy"),
                io.micrometer.core.instrument.Tag.of("status", state)), legacy, r -> r.countByStatus(state));
        }
    }

    public void dispatch() {
        for (Long id : repository.findDueIds()) {
            long start = System.nanoTime();
            try {
                var outcome = transactions.process(id);
                metrics.counter("notification.common.outbox.processing", "result", outcome.name()).increment();
            } catch (RuntimeException failure) {
                boolean retryable = NotificationPersistenceFailures.isRetryable(failure)
                    || failure instanceof DataIntegrityViolationException integrity
                    && NotificationPersistenceFailures.isDeduplicationConflict(integrity);
                metrics.counter("notification.common.outbox.processing", "result", retryable ? "RETRY" : "FAILED").increment();
                log.warn("공통 알림 Outbox 처리 실패: id={}, retryable={}, errorType={}",
                    id, retryable, failure.getClass().getName());
                try {
                    transactions.recordFailure(id, retryable, failure.getClass().getName());
                } catch (RuntimeException bookkeepingFailure) {
                    metrics.counter("notification.common.outbox.processing", "result", "BOOKKEEPING_FAILURE").increment();
                    log.error("공통 알림 Outbox 실패 기록 불가: id={}, errorType={}", id, bookkeepingFailure.getClass().getName());
                }
            } finally {
                metrics.timer("notification.common.outbox.duration").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }
}
