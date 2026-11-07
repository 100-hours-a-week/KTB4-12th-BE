package com.gift.gift.domain.order.repository;

import java.time.LocalDateTime;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentReceiptDeliveryRepository {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createPayment(Long orderRecordId, LocalDateTime paymentCompletedAt, boolean eligible) {
        insert("PAYMENT", orderRecordId, paymentCompletedAt, eligible);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createCancellation(Long cancellationId, LocalDateTime paymentCompletedAt, boolean eligible) {
        insert("CANCELLATION", cancellationId, paymentCompletedAt, eligible);
    }

    private void insert(String receiptType, Long sourceId, LocalDateTime paymentCompletedAt, boolean eligible) {

        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(paymentCompletedAt, "paymentCompletedAt");

        if (sourceId <= 0) {
            throw new IllegalArgumentException("영수증 원본 ID는 양수여야 합니다.");
        }

        jdbc.update("""
                INSERT INTO payment_receipt_deliveries (
                    receipt_type,
                    source_id,
                    payment_completed_at,
                    status
                ) VALUES (?, ?, ?, ?)
                """,
                receiptType,
                sourceId,
                paymentCompletedAt,
                eligible ? "READY" : "SKIPPED"
        );
    }
}
