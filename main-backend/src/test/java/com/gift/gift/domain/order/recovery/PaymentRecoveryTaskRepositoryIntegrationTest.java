package com.gift.gift.domain.order.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.scheduling.enabled=false")
class PaymentRecoveryTaskRepositoryIntegrationTest {

    @Autowired
    private PaymentRecoveryTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long taskId;

    @AfterEach
    void cleanUp() {
        if (taskId > 0) {
            jdbc.update(
                    "DELETE FROM payment_recovery_tasks WHERE id = ?",
                    taskId
            );
        }
    }

    @Test
    @DisplayName("만료된 동기 토큰을 회수하고 이전 소유자의 쓰기를 거부한다")
    void expiredSyncOwnerIsFenced() {
        String original = repository.createSyncTask(
                PaymentRecoveryTaskType.APPROVAL, 900001L
        );
        taskId = jdbc.queryForObject(
                """
                SELECT id FROM payment_recovery_tasks
                WHERE target_type = 'APPROVAL' AND target_id = ?
                """,
                Long.class,
                900001L
        );

        var active = repository.claimSyncOwner(
                PaymentRecoveryTaskType.APPROVAL, 900001L
        );
        assertThat(active.managed()).isTrue();
        assertThat(active.token()).isNull();

        jdbc.update("""
                UPDATE payment_recovery_tasks
                SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE id = ?
                """, taskId);

        var claimed = repository.claimSyncOwner(
                PaymentRecoveryTaskType.APPROVAL, 900001L
        );
        assertThat(claimed.managed()).isTrue();
        assertThat(claimed.token()).isNotBlank().isNotEqualTo(original);

        assertThatThrownBy(() -> repository.markSyncReady(
                PaymentRecoveryTaskType.APPROVAL,
                900001L,
                original,
                "PAYMENT_RESULT_UNCONFIRMED"
        )).isInstanceOf(StalePaymentRecoveryOwnerException.class);

        repository.markSyncReady(
                PaymentRecoveryTaskType.APPROVAL,
                900001L,
                claimed.token(),
                "PAYMENT_RESULT_UNCONFIRMED"
        );
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payment_recovery_tasks WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("READY");
    }
}
