package com.gift.gift.domain.notification.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.repository.NotificationCommonOutboxRepository;
import com.gift.gift.domain.notification.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationCommonOutboxTransactionService {
    private final NotificationCommonOutboxRepository outbox;
    private final NotificationRepository notifications;
    private final NotificationCreateTransactionService creation;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Outcome process(Long id) {
        var jobs = outbox.lockDue(id);
        if (jobs.isEmpty()) return Outcome.SKIPPED;
        var job = jobs.getFirst();
        var command = mapper.readValue(job.payload(), NotificationCreateCommand.class);
        validate(job, command);
        boolean exists = notifications.existsByDeduplicationKey(command.deduplicationKey());
        if (!exists) creation.saveInCurrentTransaction(command);
        outbox.complete(id);
        return exists ? Outcome.ALREADY_CREATED : Outcome.CREATED;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long id, boolean retryable, String errorType) {
        outbox.recordFailure(id, retryable, errorType);
    }

    private void validate(NotificationCommonOutboxRepository.Job job, NotificationCreateCommand command) {
        if (command == null || command.recipientId() == null || command.type() == null
                || job.schemaVersion() != 1 || !"USER".equals(job.aggregateType())
                || !command.recipientId().toString().equals(job.aggregateId())
                || !Objects.equals(command.deduplicationKey(), job.deduplicationKey())
                || !fits(command.sourceIdentifier(), 120) || !fits(command.deduplicationKey(), 120)
                || !fits(command.title(), 100) || !fits(command.message(), 255)
                || (command.referenceType() == null) != (command.referenceId() == null)) {
            throw new IllegalArgumentException("잘못된 알림 Outbox payload입니다.");
        }
    }

    private boolean fits(String value, int limit) {
        return value != null && value.codePointCount(0, value.length()) <= limit;
    }

    public enum Outcome { CREATED, ALREADY_CREATED, SKIPPED }
}
