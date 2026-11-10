package com.gift.gift.domain.order.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentReceiptDeliveryRepository {

    private static final String PAYMENT_MAIL_SQL = """
            SELECT
                u.email AS recipient_email,
                o.order_id,
                g.product_name_snapshot,
                g.quantity,
                tx.amount,
                tx.balance_after,
                d.payment_completed_at,
                NULL AS canceled_at
            FROM payment_receipt_deliveries d
            JOIN orders o
              ON o.id = d.source_id
             AND o.status IN ('COMPLETED', 'CANCELED')
            JOIN gift_histories g
              ON g.order_record_id = o.id
             AND g.sender_id = o.payer_id
             AND g.quantity = o.quantity
            JOIN credit_transactions tx
              ON tx.order_record_id = o.id
             AND tx.gift_history_id = g.id
             AND tx.user_id = o.payer_id
             AND tx.type = 'PAYMENT_USE'
             AND tx.amount = o.amount
             AND tx.deduplication_key = CONCAT('PAYMENT_USE:', o.id)
            JOIN users u
              ON u.id = o.payer_id
            WHERE d.receipt_type = 'PAYMENT'
              AND d.source_id = ?
            """;

    private static final String CANCELLATION_MAIL_SQL = """
            SELECT
                u.email AS recipient_email,
                o.order_id,
                g.product_name_snapshot,
                g.quantity,
                tx.amount,
                tx.balance_after,
                d.payment_completed_at,
                c.canceled_at
            FROM payment_receipt_deliveries d
            JOIN payment_cancellations c
              ON c.id = d.source_id
             AND c.status = 'COMPLETED'
             AND c.canceled_at IS NOT NULL
            JOIN orders o
              ON o.id = c.order_record_id
             AND o.payer_id = c.requester_id
             AND o.status = 'CANCELED'
            JOIN gift_histories g
              ON g.order_record_id = o.id
             AND g.sender_id = o.payer_id
             AND g.quantity = o.quantity
            JOIN credit_transactions tx
              ON tx.order_record_id = o.id
             AND tx.gift_history_id = g.id
             AND tx.user_id = o.payer_id
             AND tx.type = 'PAYMENT_REFUND'
             AND tx.amount = o.amount
             AND tx.deduplication_key = CONCAT('PAYMENT_REFUND:', o.id)
            JOIN users u
              ON u.id = o.payer_id
            WHERE d.receipt_type = 'CANCELLATION'
              AND d.source_id = ?
            """;

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createPayment(Long orderRecordId, LocalDateTime paymentCompletedAt, boolean eligible) {
        insert("PAYMENT", orderRecordId, paymentCompletedAt, eligible);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createCancellation(Long cancellationId, LocalDateTime paymentCompletedAt, boolean eligible) {
        insert("CANCELLATION", cancellationId, paymentCompletedAt, eligible);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentReceiptDelivery> findForUpdate(PaymentReceiptDeliveryType receiptType, long sourceId) {
        List<PaymentReceiptDelivery> rows = jdbc.query("""
                SELECT
                    status,
                    auto_attempts,
                    claim_token,
                    lease_until,
                    COALESCE(lease_until > CURRENT_TIMESTAMP(6), 0) AS lease_active,
                    replay_token,
                    replay_used_at
                FROM payment_receipt_deliveries
                WHERE receipt_type = ?
                  AND source_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new PaymentReceiptDelivery(
                        PaymentReceiptDeliveryStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("auto_attempts"),
                        resultSet.getString("claim_token"),
                        localDateTime(resultSet, "lease_until"),
                        resultSet.getBoolean("lease_active"),
                        resultSet.getString("replay_token"),
                        localDateTime(resultSet, "replay_used_at")
                ),
                receiptType.name(),
                sourceId
        );

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentReceiptMailData> findMailData(PaymentReceiptDeliveryType receiptType, long sourceId) {

        String sql = receiptType == PaymentReceiptDeliveryType.PAYMENT
                ? PAYMENT_MAIL_SQL
                : CANCELLATION_MAIL_SQL;

        List<PaymentReceiptMailData> rows = jdbc.query(
                sql,
                (resultSet, rowNumber) -> new PaymentReceiptMailData(
                        resultSet.getString("recipient_email"),
                        resultSet.getString("order_id"),
                        resultSet.getString("product_name_snapshot"),
                        resultSet.getInt("quantity"),
                        resultSet.getLong("amount"),
                        resultSet.getLong("balance_after"),
                        localDateTime(resultSet, "payment_completed_at"),
                        localDateTime(resultSet, "canceled_at")
                ),
                sourceId
        );

        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int claimAutomatic(PaymentReceiptDeliveryType receiptType, long sourceId, String claimToken) {
        return jdbc.update("""
                UPDATE payment_receipt_deliveries
                SET status = 'SENDING',
                    auto_attempts = auto_attempts + 1,
                    claim_token = ?,
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND),
                    accepted_at = NULL,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE receipt_type = ?
                  AND source_id = ?
                  AND auto_attempts < 4
                  AND (
                        status = 'READY'
                        OR (
                            status = 'SENDING'
                            AND lease_until <= CURRENT_TIMESTAMP(6)
                            AND replay_used_at IS NULL
                        )
                  )
                """,
                claimToken,
                receiptType.name(),
                sourceId
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int claimManual(PaymentReceiptDeliveryType receiptType, long sourceId, String claimToken, String replayToken) {
        return jdbc.update("""
                UPDATE payment_receipt_deliveries
                SET status = 'SENDING',
                    claim_token = ?,
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND),
                    replay_used_at = CURRENT_TIMESTAMP(6),
                    accepted_at = NULL,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE receipt_type = ?
                  AND source_id = ?
                  AND replay_token = ?
                  AND replay_used_at IS NULL
                  AND (
                        status IN ('READY', 'FAILED')
                        OR (
                            status = 'SENDING'
                            AND lease_until <= CURRENT_TIMESTAMP(6)
                        )
                  )
                """,
                claimToken,
                receiptType.name(),
                sourceId,
                replayToken
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markFailedLocked(PaymentReceiptDeliveryType receiptType, long sourceId, String errorCode) {
        return jdbc.update("""
                UPDATE payment_receipt_deliveries
                SET status = 'FAILED',
                    claim_token = NULL,
                    lease_until = NULL,
                    accepted_at = NULL,
                    last_error_code = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE receipt_type = ?
                  AND source_id = ?
                  AND status IN ('READY', 'SENDING')
                """,
                errorCode,
                receiptType.name(),
                sourceId
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markSent(PaymentReceiptDeliveryType receiptType, long sourceId, String claimToken) {
        return jdbc.update("""
                UPDATE payment_receipt_deliveries
                SET status = 'SENT',
                    claim_token = NULL,
                    lease_until = NULL,
                    accepted_at = CURRENT_TIMESTAMP(6),
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE receipt_type = ?
                  AND source_id = ?
                  AND status = 'SENDING'
                  AND claim_token = ?
                """,
                receiptType.name(),
                sourceId,
                claimToken
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int recordFailure(PaymentReceiptDeliveryType receiptType, long sourceId, String claimToken, String errorCode, boolean retryable) {
        String nextStatus = retryable ? "READY" : "FAILED";
        String retryGuard = retryable ? " AND auto_attempts < 4 AND replay_used_at IS NULL" : "";

        return jdbc.update("""
                UPDATE payment_receipt_deliveries
                SET status = ?,
                    claim_token = NULL,
                    lease_until = NULL,
                    accepted_at = NULL,
                    last_error_code = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE receipt_type = ?
                  AND source_id = ?
                  AND status = 'SENDING'
                  AND claim_token = ?
                """ + retryGuard,
                nextStatus,
                errorCode,
                receiptType.name(),
                sourceId,
                claimToken
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentReceiptDeliveryStatus> findStatus(PaymentReceiptDeliveryType receiptType, long sourceId) {
        List<PaymentReceiptDeliveryStatus> rows = jdbc.query("""
                SELECT status
                FROM payment_receipt_deliveries
                WHERE receipt_type = ?
                  AND source_id = ?
                """,
                (resultSet, rowNumber) -> PaymentReceiptDeliveryStatus.valueOf(resultSet.getString("status")),
                receiptType.name(),
                sourceId
        );

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
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

    private static LocalDateTime localDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
