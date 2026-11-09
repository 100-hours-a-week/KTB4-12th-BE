package com.gift.gift.domain.order.recovery;

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

    public List<DispatchJob> claimDue(int limit, int leaseSeconds) {
        return repository.claimDue(limit, leaseSeconds);
    }

    public void retryDispatch(DispatchJob job, String errorCode) {
        repository.retryDispatch(job, errorCode);
    }
}
