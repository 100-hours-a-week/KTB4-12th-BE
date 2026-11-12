package com.gift.gift.domain.order.recovery;

import java.time.Duration;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnExpression(
        "${app.payment.mq.topology.enabled:false} and "
                + "${app.payment.recovery.dispatch.enabled:false}"
)
public class PaymentRecoveryTaskMigrationService {

    private final JdbcTemplate jdbc;
    private final Duration timeout;
    private final Duration staleAge;

    public PaymentRecoveryTaskMigrationService(
            JdbcTemplate jdbc,
            @Value("${app.payment.reconciliation.timeout:PT10M}") Duration timeout,
            @Value("${app.payment.reconciliation.stale-age:PT1M}") Duration staleAge
    ) {
        this.jdbc = jdbc;
        this.timeout = timeout;
        this.staleAge = staleAge;
    }

    @PostConstruct
    public void migrateLegacyTasks() {
        insertMissingApprovalTasks();
        insertMissingCancellationTasks();
        syncUnpublishedTaskState();
        markLegacyReviewTasks();

        log.info("결제 복구 작업의 기존 상태 이관을 완료했습니다.");
    }

    private void insertMissingApprovalTasks() {
        jdbc.update("""
                INSERT INTO payment_recovery_tasks (
                    target_type,
                    target_id,
                    status,
                    started_at,
                    deadline_at,
                    next_run_at,
                    reconciliation_attempts
                )
                SELECT
                    'APPROVAL',
                    orders.id,
                    'READY',
                    COALESCE(orders.reconciliation_started_at, orders.created_at),
                    TIMESTAMPADD(
                        SECOND,
                        ?,
                        COALESCE(orders.reconciliation_started_at, orders.created_at)
                    ),
                    CASE
                        WHEN orders.next_reconciliation_at IS NOT NULL
                            THEN orders.next_reconciliation_at
                        WHEN orders.approval_result IS NULL
                            THEN TIMESTAMPADD(SECOND, 10, orders.created_at)
                        WHEN orders.approval_result = 'APPROVED'
                            THEN TIMESTAMPADD(MICROSECOND, ?, orders.updated_at)
                        ELSE TIMESTAMPADD(
                            SECOND,
                            5,
                            COALESCE(
                                orders.reconciliation_started_at,
                                orders.created_at
                            )
                        )
                    END,
                    orders.reconciliation_attempts
                FROM orders
                WHERE orders.status = 'PENDING'
                  AND (
                        orders.approval_result IS NULL
                        OR orders.approval_result = 'UNKNOWN'
                        OR (
                            orders.approval_result = 'APPROVED'
                            AND (
                                orders.failure_code IS NULL
                                OR orders.failure_code IN (
                                    'LOCAL_PROCESSING_FAILED',
                                    'AUTO_CANCELLATION_PROCESSING'
                                )
                            )
                        )
                  )
                  AND (
                        orders.failure_code IS NULL
                        OR orders.failure_code NOT IN (
                            'PAYMENT_RECONCILIATION_REQUIRED',
                            'AUTO_CANCELLATION_RECONCILIATION_REQUIRED'
                        )
                  )
                ON DUPLICATE KEY UPDATE payment_recovery_tasks.id = payment_recovery_tasks.id
                """, timeout.toSeconds(), staleAge.toNanos() / 1_000);
    }

    private void insertMissingCancellationTasks() {
        jdbc.update("""
                INSERT INTO payment_recovery_tasks (
                    target_type,
                    target_id,
                    status,
                    started_at,
                    deadline_at,
                    next_run_at,
                    reconciliation_attempts,
                    cancel_post_attempts
                )
                SELECT
                    'CANCELLATION',
                    cancellations.id,
                    'READY',
                    COALESCE(
                        cancellations.reconciliation_started_at,
                        cancellations.created_at
                    ),
                    TIMESTAMPADD(
                        SECOND,
                        ?,
                        COALESCE(
                            cancellations.reconciliation_started_at,
                            cancellations.created_at
                        )
                    ),
                    COALESCE(
                        cancellations.next_reconciliation_at,
                        TIMESTAMPADD(
                            SECOND,
                            5,
                            COALESCE(
                                cancellations.reconciliation_started_at,
                                cancellations.created_at
                            )
                        )
                    ),
                    cancellations.reconciliation_attempts,
                    cancellations.recovery_cancel_post_attempts
                FROM payment_cancellations cancellations
                WHERE cancellations.cancellation_source = 'USER'
                  AND cancellations.status = 'PENDING'
                  AND (
                        cancellations.failure_code IS NULL
                        OR cancellations.failure_code <>
                            'CANCELLATION_RECONCILIATION_REQUIRED'
                  )
                ON DUPLICATE KEY UPDATE payment_recovery_tasks.id = payment_recovery_tasks.id
                """, timeout.toSeconds());
    }

    private void syncUnpublishedTaskState() {
        jdbc.update("""
                UPDATE payment_recovery_tasks tasks
                JOIN orders
                  ON tasks.target_type = 'APPROVAL'
                 AND tasks.target_id = orders.id
                SET tasks.started_at = COALESCE(
                        orders.reconciliation_started_at,
                        orders.created_at
                    ),
                    tasks.deadline_at = TIMESTAMPADD(
                        SECOND,
                        ?,
                        COALESCE(
                            orders.reconciliation_started_at,
                            orders.created_at
                        )
                    ),
                    tasks.next_run_at = CASE
                        WHEN orders.next_reconciliation_at IS NOT NULL
                            THEN orders.next_reconciliation_at
                        WHEN orders.approval_result IS NULL
                            THEN TIMESTAMPADD(SECOND, 10, orders.created_at)
                        WHEN orders.approval_result = 'APPROVED'
                            THEN TIMESTAMPADD(MICROSECOND, ?, orders.updated_at)
                        ELSE TIMESTAMPADD(
                            SECOND,
                            5,
                            COALESCE(
                                orders.reconciliation_started_at,
                                orders.created_at
                            )
                        )
                    END,
                    tasks.reconciliation_attempts = GREATEST(
                        tasks.reconciliation_attempts,
                        orders.reconciliation_attempts
                    )
                WHERE tasks.status = 'READY'
                  AND tasks.generation = 0
                  AND orders.status = 'PENDING'
                  AND (
                        orders.approval_result IS NULL
                        OR orders.approval_result = 'UNKNOWN'
                        OR (
                            orders.approval_result = 'APPROVED'
                            AND (
                                orders.failure_code IS NULL
                                OR orders.failure_code IN (
                                    'LOCAL_PROCESSING_FAILED',
                                    'AUTO_CANCELLATION_PROCESSING'
                                )
                            )
                        )
                  )
                  AND (
                        orders.failure_code IS NULL
                        OR orders.failure_code NOT IN (
                            'PAYMENT_RECONCILIATION_REQUIRED',
                            'AUTO_CANCELLATION_RECONCILIATION_REQUIRED'
                        )
                  )
                """, timeout.toSeconds(), staleAge.toNanos() / 1_000);

        jdbc.update("""
                UPDATE payment_recovery_tasks tasks
                JOIN payment_cancellations cancellations
                  ON tasks.target_type = 'CANCELLATION'
                 AND tasks.target_id = cancellations.id
                SET tasks.started_at = COALESCE(
                        cancellations.reconciliation_started_at,
                        cancellations.created_at
                    ),
                    tasks.deadline_at = TIMESTAMPADD(
                        SECOND,
                        ?,
                        COALESCE(
                            cancellations.reconciliation_started_at,
                            cancellations.created_at
                        )
                    ),
                    tasks.next_run_at = COALESCE(
                        cancellations.next_reconciliation_at,
                        TIMESTAMPADD(
                            SECOND,
                            5,
                            COALESCE(
                                cancellations.reconciliation_started_at,
                                cancellations.created_at
                            )
                        )
                    ),
                    tasks.reconciliation_attempts = GREATEST(
                        tasks.reconciliation_attempts,
                        cancellations.reconciliation_attempts
                    ),
                    tasks.cancel_post_attempts = GREATEST(
                        tasks.cancel_post_attempts,
                        cancellations.recovery_cancel_post_attempts
                    )
                WHERE tasks.status = 'READY'
                  AND tasks.generation = 0
                  AND cancellations.cancellation_source = 'USER'
                  AND cancellations.status = 'PENDING'
                  AND (
                        cancellations.failure_code IS NULL
                        OR cancellations.failure_code <>
                            'CANCELLATION_RECONCILIATION_REQUIRED'
                  )
                """, timeout.toSeconds());
    }

    private void markLegacyReviewTasks() {
        jdbc.update("""
                UPDATE payment_recovery_tasks tasks
                JOIN orders
                  ON tasks.target_type = 'APPROVAL'
                 AND tasks.target_id = orders.id
                SET tasks.status = 'REVIEW_REQUIRED',
                    tasks.next_run_at = NULL,
                    tasks.last_error_code = orders.failure_code,
                    tasks.claim_token = NULL,
                    tasks.lease_until = NULL,
                    tasks.updated_at = CURRENT_TIMESTAMP(6)
                WHERE tasks.status IN ('READY', 'WAITING_FOR_SYNC')
                  AND orders.failure_code IN (
                        'PAYMENT_RECONCILIATION_REQUIRED',
                        'AUTO_CANCELLATION_RECONCILIATION_REQUIRED'
                  )
                """);

        jdbc.update("""
                UPDATE payment_recovery_tasks tasks
                JOIN payment_cancellations cancellations
                  ON tasks.target_type = 'CANCELLATION'
                 AND tasks.target_id = cancellations.id
                SET tasks.status = 'REVIEW_REQUIRED',
                    tasks.next_run_at = NULL,
                    tasks.last_error_code = cancellations.failure_code,
                    tasks.claim_token = NULL,
                    tasks.lease_until = NULL,
                    tasks.updated_at = CURRENT_TIMESTAMP(6)
                WHERE tasks.status IN ('READY', 'WAITING_FOR_SYNC')
                  AND cancellations.failure_code =
                        'CANCELLATION_RECONCILIATION_REQUIRED'
                """);
    }
}
