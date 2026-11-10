package com.gift.gift.domain.order.recovery;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskRepository.DispatchJob;

@Service
@RequiredArgsConstructor
public class PaymentRecoveryTaskService {

    private final PaymentRecoveryTaskRepository repository;

    public String createSyncTask(PaymentRecoveryTaskType type, long targetId) {
        return repository.createSyncTask(type, targetId);
    }

    public PaymentRecoveryTaskClaim claimSyncOwner(
            PaymentRecoveryTaskType type,
            long targetId
    ) {
        return repository.claimSyncOwner(type, targetId);
    }

    public void requireSyncOwner(
            PaymentRecoveryTaskType type,
            long targetId,
            String token
    ) {
        repository.requireSyncOwner(type, targetId, token);
    }

    public void markSyncReady(
            PaymentRecoveryTaskType type,
            long targetId,
            String token,
            String errorCode
    ) {
        repository.markSyncReady(type, targetId, token, errorCode);
    }

    public void scheduleNext(
            PaymentRecoveryTaskType type,
            long targetId,
            String token,
            LocalDateTime nextRunAt,
            String errorCode
    ) {
        repository.scheduleNext(type, targetId, token, nextRunAt, errorCode);
    }

    public void markSyncReview(
            PaymentRecoveryTaskType type,
            long targetId,
            String token,
            String errorCode
    ) {
        repository.markSyncReview(type, targetId, token, errorCode);
    }

    public void completeSync(
            PaymentRecoveryTaskType type,
            long targetId,
            String token
    ) {
        repository.completeSync(type, targetId, token);
    }

    public DispatchJob claimWorker(
            long taskId,
            PaymentRecoveryTaskType expectedType,
            long generation,
            int leaseSeconds
    ) {
        return repository.claimWorker(
                taskId, expectedType, generation, leaseSeconds
        );
    }

    public boolean isWrongTargetType(
            long taskId,
            PaymentRecoveryTaskType expectedType
    ) {
        return repository.isWrongTargetType(taskId, expectedType);
    }

    public void retryWorkerDelivery(DispatchJob job) {
        repository.retryWorkerDelivery(job);
    }

    public boolean reserveCancelPost(
            long cancellationId,
            String token,
            int maxAttempts
    ) {
        return repository.reserveCancelPost(cancellationId, token, maxAttempts);
    }

    public List<DispatchJob> claimDue(int limit, int leaseSeconds) {
        return repository.claimDue(limit, leaseSeconds);
    }

    public void retryDispatch(DispatchJob job, String errorCode) {
        repository.retryDispatch(job, errorCode);
    }
}
