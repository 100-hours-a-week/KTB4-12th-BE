package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;
import com.gift.gift.domain.notification.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationOutboxTransactionService {
    private final NotificationOutboxRepository outboxRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationCreateTransactionService createTransactionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void process(Long id) {
        for (var job : outboxRepository.lockDue(id)) {
            if (!notificationRepository.existsByDeduplicationKey(job.command().deduplicationKey())) {
                createTransactionService.saveInCurrentTransaction(job.command());
            }
            outboxRepository.complete(id);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long id, boolean retryable, String errorType) {
        outboxRepository.recordFailure(id, retryable, errorType);
    }
}
