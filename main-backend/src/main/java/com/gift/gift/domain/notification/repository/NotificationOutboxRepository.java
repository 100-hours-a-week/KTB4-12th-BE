package com.gift.gift.domain.notification.repository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;

@Repository
@RequiredArgsConstructor
public class NotificationOutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public void insert(NotificationCreateCommand command) {
        jdbcTemplate.update("""
                INSERT INTO notification_outbox
                    (recipient_id, source_identifier, type, reference_type, reference_id,
                     title, message, deduplication_key, status, failure_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP(6),
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id = id
                """, command.recipientId(), command.sourceIdentifier(), command.type().name(),
                command.referenceType() == null ? null : command.referenceType().name(), command.referenceId(),
                command.title(), command.message(), command.deduplicationKey());
    }

    public List<Long> findDueIds() {
        return jdbcTemplate.queryForList("""
                SELECT id FROM notification_outbox
                WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_attempt_at, id LIMIT 20
                """, Long.class);
    }

    public List<Job> lockDue(Long id) {
        return jdbcTemplate.query("""
                SELECT * FROM notification_outbox
                WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> {
            String referenceType = rs.getString("reference_type");
            return new Job(rs.getLong("id"), new NotificationCreateCommand(
                    rs.getLong("recipient_id"), rs.getString("source_identifier"),
                    NotificationType.valueOf(rs.getString("type")),
                    referenceType == null ? null : NotificationReferenceType.valueOf(referenceType),
                    rs.getObject("reference_id", Long.class), rs.getString("title"),
                    rs.getString("message"), rs.getString("deduplication_key")));
        }, id);
    }

    public void complete(Long id) {
        jdbcTemplate.update("""
                UPDATE notification_outbox SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6), last_error_type = NULL WHERE id = ?
                """, id);
    }

    public void recordFailure(Long id, boolean retryable, String errorType) {
        // The guarded update serializes failure bookkeeping and never changes a completed job.
        jdbcTemplate.update("""
                UPDATE notification_outbox
                SET status = ?, next_attempt_at = TIMESTAMPADD(SECOND,
                    LEAST(300, POWER(2, LEAST(failure_count, 9))), CURRENT_TIMESTAMP(6)),
                    failure_count = LEAST(failure_count + 1, 2147483647), last_error_type = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'PENDING'
                """, retryable ? "PENDING" : "FAILED", errorType, id);
    }

    public record Job(Long id, NotificationCreateCommand command) {
    }
}
