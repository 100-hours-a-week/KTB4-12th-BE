package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.repository.NotificationReplayRepository;

@Service
@RequiredArgsConstructor
public class NotificationReplayService {
    private final NotificationReplayRepository repository;

    @Transactional
    public String replay(long id, boolean legacy, boolean execute) {
        var job = repository.findFailed(id, legacy);
        if (job.isEmpty()) return "NOT_FAILED_OR_MISSING";
        if (!execute) return "DRY_RUN_ELIGIBLE";
        return repository.requeueIfUnchanged(job.get()) ? "REQUEUED" : "STALE";
    }
}
