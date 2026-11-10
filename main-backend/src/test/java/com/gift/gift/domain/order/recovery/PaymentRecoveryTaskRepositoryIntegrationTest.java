package com.gift.gift.domain.order.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.payment.mq.topology.enabled=false",
        "app.payment.recovery.dispatch.enabled=false"
})
class PaymentRecoveryTaskRepositoryIntegrationTest {

    @Autowired
    private PaymentRecoveryTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long targetId;
    private long taskId;

    @AfterEach
    void cleanUp() {
        if (targetId > 0) {
            jdbc.update("""
                    DELETE FROM payment_recovery_tasks
                    WHERE target_type = 'APPROVAL' AND target_id = ?
                    """, targetId);
        }
    }

    @Test
    @DisplayName("중복 전달은 한 Worker만 선점하고 재전달은 새 토큰을 사용한다")
    void workerClaimFencesDuplicateAndOldOwner() {
        targetId = 9_100_000L + System.nanoTime() % 100_000L;
        repository.createSyncTask(PaymentRecoveryTaskType.APPROVAL, targetId);

        taskId = jdbc.queryForObject("""
                SELECT id
                FROM payment_recovery_tasks
                WHERE target_type = 'APPROVAL' AND target_id = ?
                """, Long.class, targetId);

        jdbc.update("""
                UPDATE payment_recovery_tasks
                SET status = 'DISPATCHED',
                    generation = 1,
                    next_run_at = NULL,
                    lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6))
                WHERE id = ?
                """, taskId);

        var first = repository.claimWorker(
                taskId,
                PaymentRecoveryTaskType.APPROVAL,
                1L,
                60
        );

        assertThat(first).isNotNull();
        assertThat(repository.claimWorker(
                taskId,
                PaymentRecoveryTaskType.APPROVAL,
                1L,
                60
        )).isNull();

        repository.requireSyncOwner(
                PaymentRecoveryTaskType.APPROVAL,
                targetId,
                first.claimToken()
        );

        repository.retryWorkerDelivery(first);

        var redelivery = repository.claimWorker(
                taskId,
                PaymentRecoveryTaskType.APPROVAL,
                1L,
                60
        );

        assertThat(redelivery).isNotNull();
        assertThat(redelivery.claimToken()).isNotEqualTo(first.claimToken());

        assertThatThrownBy(() -> repository.completeSync(
                PaymentRecoveryTaskType.APPROVAL,
                targetId,
                first.claimToken()
        )).isInstanceOf(StalePaymentRecoveryOwnerException.class);

        repository.completeSync(
                PaymentRecoveryTaskType.APPROVAL,
                targetId,
                redelivery.claimToken()
        );

        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM payment_recovery_tasks
                WHERE id = ?
                """, String.class, taskId)).isEqualTo("COMPLETED");
    }
}
