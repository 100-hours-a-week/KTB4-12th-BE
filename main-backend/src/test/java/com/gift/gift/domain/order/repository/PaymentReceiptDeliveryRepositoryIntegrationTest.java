package com.gift.gift.domain.order.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.outbox.enabled=false"
})
class PaymentReceiptDeliveryRepositoryIntegrationTest {

    private static final LocalDateTime PAYMENT_COMPLETED_AT =
            LocalDateTime.of(2026, 11, 7, 12, 30, 45, 123_000_000);

    @Autowired
    private PaymentReceiptDeliveryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final long sourceId = Math.max(1, System.nanoTime());

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                DELETE FROM payment_receipt_deliveries
                WHERE source_id IN (?, ?, ?)
                """, sourceId, sourceId + 1, sourceId + 2);
    }

    @Test
    @DisplayName("거래 시점 인증 여부를 READY와 SKIPPED로 저장한다")
    void createsPaymentAndCancellationRowsWithEligibility() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            repository.createPayment(sourceId, PAYMENT_COMPLETED_AT, true);
            repository.createCancellation(
                    sourceId + 1,
                    PAYMENT_COMPLETED_AT,
                    false
            );
        });

        assertThat(statusOf("PAYMENT", sourceId)).isEqualTo("READY");
        assertThat(statusOf("CANCELLATION", sourceId + 1)).isEqualTo("SKIPPED");
        assertThat(paymentCompletedAt("PAYMENT", sourceId)).isEqualTo(PAYMENT_COMPLETED_AT);
    }

    @Test
    @DisplayName("호출한 업무 트랜잭션이 롤백되면 영수증 행도 롤백한다")
    void rollsBackWithBusinessTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            repository.createPayment(
                    sourceId + 2,
                    PAYMENT_COMPLETED_AT,
                    true
            );
            status.setRollbackOnly();
        });

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM payment_receipt_deliveries
                WHERE receipt_type = 'PAYMENT' AND source_id = ?
                """, Integer.class, sourceId + 2);

        assertThat(count).isZero();
    }

    private String statusOf(String receiptType, long id) {
        return jdbc.queryForObject("""
                SELECT status
                FROM payment_receipt_deliveries
                WHERE receipt_type = ? AND source_id = ?
                """, String.class, receiptType, id);
    }

    private LocalDateTime paymentCompletedAt(String receiptType, long id) {
        Timestamp value = jdbc.queryForObject("""
                SELECT payment_completed_at
                FROM payment_receipt_deliveries
                WHERE receipt_type = ? AND source_id = ?
                """, Timestamp.class, receiptType, id);

        return value.toLocalDateTime();
    }
}
