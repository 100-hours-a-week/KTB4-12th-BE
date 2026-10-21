package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;
import com.gift.gift.domain.notification.support.NotificationPersistenceFailures;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxDispatcher {
    private final NotificationOutboxRepository repository;
    private final NotificationOutboxTransactionService transactionService;

    public void dispatch() {
        for (Long id : repository.findDueIds()) {
            try {
                transactionService.process(id);
            } catch (RuntimeException failure) {
                boolean retryable = NotificationPersistenceFailures.isRetryable(failure)
                        || (failure instanceof DataIntegrityViolationException integrity
                        && NotificationPersistenceFailures.isDeduplicationConflict(integrity));
                log.warn("알림 Outbox 처리 실패: id={}, retryable={}, errorType={}",
                        id, retryable, failure.getClass().getName());
                try {
                    transactionService.recordFailure(id, retryable, failure.getClass().getName());
                } catch (RuntimeException bookkeepingFailure) {
                    log.error("알림 Outbox 실패 기록 불가: id={}, errorType={}",
                            id, bookkeepingFailure.getClass().getName());
                }
            }
        }
    }
}
