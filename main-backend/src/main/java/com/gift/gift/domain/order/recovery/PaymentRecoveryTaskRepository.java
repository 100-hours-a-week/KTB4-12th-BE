package com.gift.gift.domain.order.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentRecoveryTaskRepository {

    private final JdbcTemplate jdbc;

    public String createSyncTask(PaymentRecoveryTaskType type, long targetId) {
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO payment_recovery_tasks (
                    target_type, target_id, status, claim_token, lease_until
                ) VALUES (?, ?, 'WAITING_FOR_SYNC', ?,
                    TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(6)))
                """, type.name(), targetId, token);
        return token;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentRecoveryTaskClaim claimSyncOwner(
            PaymentRecoveryTaskType type,
            long targetId
    ) {
        List<String> rows = jdbc.query("""
                SELECT status
                FROM payment_recovery_tasks
                WHERE target_type = ? AND target_id = ?
                """, (rs, row) -> rs.getString("status"), type.name(), targetId);

        if (rows.isEmpty()) {
            return PaymentRecoveryTaskClaim.unmanaged();
        }

        if (!PaymentRecoveryTaskStatus.WAITING_FOR_SYNC.name().equals(rows.getFirst())) {
            return PaymentRecoveryTaskClaim.managed(null);
        }

        String token = UUID.randomUUID().toString();
        int updated = jdbc.update("""
                UPDATE payment_recovery_tasks
                SET claim_token = ?,
                    lease_until = TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(6)),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE target_type = ? AND target_id = ?
                  AND status = 'WAITING_FOR_SYNC'
                  AND lease_until <= CURRENT_TIMESTAMP(6)
                """, token, type.name(), targetId);

        return PaymentRecoveryTaskClaim.managed(updated == 1 ? token : null);
    }

    public void requireSyncOwner(
            PaymentRecoveryTaskType type,
            long targetId,
            String token
    ) {
        List<Long> rows = jdbc.query("""
                SELECT id
                FROM payment_recovery_tasks
                WHERE target_type = ? AND target_id = ?
                  AND status = 'WAITING_FOR_SYNC'
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                FOR UPDATE
                """, (rs, row) -> rs.getLong("id"), type.name(), targetId, token);

        if (rows.isEmpty()) {
            throw new StalePaymentRecoveryOwnerException();
        }
    }

    public void markSyncReady(
            PaymentRecoveryTaskType type,
            long targetId,
            String token,
            String errorCode
    ) {
        int updated = jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'READY',
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6)),
                    deadline_at = COALESCE(
                        deadline_at,
                        TIMESTAMPADD(MINUTE, 10, CURRENT_TIMESTAMP(6))
                    ),
                    next_run_at = CASE
                        WHEN deadline_at IS NULL
                            THEN TIMESTAMPADD(SECOND, 5, CURRENT_TIMESTAMP(6))
                        ELSE LEAST(
                            deadline_at,
                            TIMESTAMPADD(SECOND, 5, CURRENT_TIMESTAMP(6))
                        )
                    END,
                    last_error_code = ?,
                    claim_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE target_type = ? AND target_id = ?
                  AND status = 'WAITING_FOR_SYNC'
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, errorCode, type.name(), targetId, token);

        if (updated != 1) {
            throw new StalePaymentRecoveryOwnerException();
        }
    }

    public void markSyncReview(
            PaymentRecoveryTaskType type,
            long targetId,
            String token,
            String errorCode
    ) {
        int updated = jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'REVIEW_REQUIRED',
                    next_run_at = NULL,
                    last_error_code = ?,
                    claim_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE target_type = ? AND target_id = ?
                  AND status = 'WAITING_FOR_SYNC'
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, errorCode, type.name(), targetId, token);

        if (updated != 1) {
            throw new StalePaymentRecoveryOwnerException();
        }
    }

    public void completeSync(
            PaymentRecoveryTaskType type,
            long targetId,
            String token
    ) {
        int updated = jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'COMPLETED',
                    next_run_at = NULL,
                    claim_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE target_type = ? AND target_id = ?
                  AND status = 'WAITING_FOR_SYNC'
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, type.name(), targetId, token);

        if (updated != 1) {
            throw new StalePaymentRecoveryOwnerException();
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public List<DispatchJob> claimDue(int limit, int leaseSeconds) {
        if (limit < 1 || limit > 100 || leaseSeconds < 1) {
            throw new IllegalArgumentException("복구 발행 선점 설정이 올바르지 않습니다.");
        }

        jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'READY',
                    started_at = lease_until,
                    deadline_at = TIMESTAMPADD(MINUTE, 10, lease_until),
                    next_run_at = TIMESTAMPADD(SECOND, 5, lease_until),
                    claim_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE status = 'WAITING_FOR_SYNC'
                  AND lease_until <= CURRENT_TIMESTAMP(6)
                """);

        jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'READY',
                    next_run_at = CURRENT_TIMESTAMP(6),
                    claim_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE status IN ('DISPATCHED', 'RUNNING')
                  AND lease_until <= CURRENT_TIMESTAMP(6)
                """);

        List<DispatchJob> due = jdbc.query("""
                SELECT id, target_type, target_id, generation
                FROM payment_recovery_tasks
                WHERE status = 'READY'
                  AND next_run_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_run_at, id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new DispatchJob(
                rs.getLong("id"),
                PaymentRecoveryTaskType.valueOf(rs.getString("target_type")),
                rs.getLong("target_id"),
                rs.getLong("generation") + 1,
                UUID.randomUUID().toString()
        ), limit);

        List<DispatchJob> claimed = new ArrayList<>(due.size());
        for (DispatchJob job : due) {
            int updated = jdbc.update("""
                    UPDATE payment_recovery_tasks
                    SET status = 'DISPATCHED',
                        generation = ?,
                        next_run_at = NULL,
                        claim_token = ?,
                        lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)),
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ?
                      AND status = 'READY'
                      AND generation = ?
                    """, job.generation(), job.claimToken(), leaseSeconds,
                    job.id(), job.generation() - 1);
            if (updated == 1) {
                claimed.add(job);
            }
        }
        return claimed;
    }

    public void retryDispatch(DispatchJob job, String errorCode) {
        jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'READY',
                    next_run_at = CURRENT_TIMESTAMP(6),
                    last_error_code = ?,
                    claim_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND status = 'DISPATCHED'
                  AND generation = ?
                  AND claim_token = ?
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, errorCode, job.id(), job.generation(), job.claimToken());
    }

    public record DispatchJob(
            long id,
            PaymentRecoveryTaskType targetType,
            long targetId,
            long generation,
            String claimToken
    ) {
    }
}
