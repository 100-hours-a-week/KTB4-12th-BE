package com.gift.gift.domain.notification.repository;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;

@Repository
@RequiredArgsConstructor
public class NotificationCommonOutboxRepository {
    public static final String EVENT_TYPE = "notification.create.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Value("${app.notification.outbox.batch-size:100}")
    private int batchSize = 100;

    @PostConstruct
    void validateBatchSize() {
        if (batchSize <= 0) throw new IllegalArgumentException("알림 Outbox 배치 크기는 양수여야 합니다.");
    }

    public void insert(NotificationCreateCommand command) {
        jdbc.update("""
                        INSERT INTO outbox_event (event_id, event_type, schema_version, deduplication_key,
                            aggregate_type, aggregate_id, payload, occurred_at, trace_id)
                        VALUES (?, 'notification.create.v1', 1, ?, 'USER', ?, ?, CURRENT_TIMESTAMP(6), ?) AS incoming
                        ON DUPLICATE KEY UPDATE event_id = IF(
                            outbox_event.event_type = incoming.event_type
                            AND outbox_event.deduplication_key = incoming.deduplication_key,
                            outbox_event.event_id, NULL)
                        """, UUID.randomUUID().toString(), command.deduplicationKey(), command.recipientId().toString(),
                mapper.writeValueAsString(command), MDC.get("traceId"));
    }

    public List<Long> findDueIds() {
        return jdbc.queryForList("""
                SELECT id FROM outbox_event
                WHERE event_type = 'notification.create.v1'
                    AND status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_attempt_at, id LIMIT ?
                """, Long.class, batchSize);
    }

    public List<Job> lockDue(Long id) {
        return jdbc.query("""
                SELECT schema_version, deduplication_key, aggregate_type, aggregate_id, payload
                FROM outbox_event
                WHERE id = ? AND event_type = 'notification.create.v1'
                    AND status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Job(rs.getInt("schema_version"), rs.getString("deduplication_key"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("payload")), id);
    }

    public void complete(Long id) {
        int updated = jdbc.update("""
                UPDATE outbox_event SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6), last_error_type = NULL
                WHERE id = ? AND event_type = 'notification.create.v1' AND status = 'PENDING'
                """, id);
        if (updated != 1) throw new IllegalStateException("잠근 알림 Outbox를 완료하지 못했습니다.");
    }

    public void recordFailure(Long id, boolean retryable, String errorType) {
        jdbc.update("""
                UPDATE outbox_event
                SET status = ?, next_attempt_at = TIMESTAMPADD(SECOND,
                    LEAST(300, POWER(2, LEAST(failure_count, 9))), CURRENT_TIMESTAMP(6)),
                    failure_count = LEAST(failure_count + 1, 2147483647), last_error_type = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND event_type = 'notification.create.v1' AND status = 'PENDING'
                """, retryable ? "PENDING" : "FAILED", errorType, id);
    }

    public long countByStatus(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND status = ?",
                Long.class, EVENT_TYPE, status);
    }

    public record Job(int schemaVersion, String deduplicationKey, String aggregateType,
                      String aggregateId, String payload) {
    }
}
