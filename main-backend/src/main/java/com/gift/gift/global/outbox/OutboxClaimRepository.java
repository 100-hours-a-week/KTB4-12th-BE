package com.gift.gift.global.outbox;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.MessageEnvelope;

@Repository
@RequiredArgsConstructor
public class OutboxClaimRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OutboxProperties settings;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<Job> claim(String eventType, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("선점 크기는 1~1000이어야 합니다.");
        }
        List<Job> expired = jdbc.query("""
                        SELECT * FROM outbox_event
                        WHERE event_type = ? AND status = 'PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)
                        ORDER BY lease_until, id LIMIT ? FOR UPDATE SKIP LOCKED
                        """, (rs, row) -> new Job(rs.getLong("id"), rs.getString("claim_token"), null),
                eventType, limit);
        for (Job job : expired) {
            updateFailure(job, "LeaseExpired", true);
        }
        String token = UUID.randomUUID().toString();
        List<Job> jobs = jdbc.query("""
                SELECT * FROM outbox_event
                WHERE event_type = ? AND status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Job(rs.getLong("id"), token, new MessageEnvelope(
                rs.getString("event_id"), rs.getString("event_type"), rs.getInt("schema_version"),
                rs.getString("deduplication_key"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getObject("aggregate_version", Long.class), mapper.readTree(rs.getString("payload")),
                rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getString("trace_id"))), eventType, limit);
        for (Job job : jobs) {
            jdbc.update("""
                    UPDATE outbox_event SET status = 'PUBLISHING', claim_token = ?,
                        lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)),
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ?
                    """, token, settings.leaseSeconds(), job.id());
        }
        return jobs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Job job) {
        return jdbc.update("""
                UPDATE outbox_event SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6),
                    claim_token = NULL, lease_until = NULL, last_error_type = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'PUBLISHING' AND claim_token = ?
                    AND lease_until > CURRENT_TIMESTAMP(6)
                """, job.id(), job.claimToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Job job, String errorType) {
        updateFailure(job, errorType, false);
    }

    private void updateFailure(Job job, String errorType, boolean recovering) {
        jdbc.update("""
                        UPDATE outbox_event
                        SET status = CASE WHEN failure_count + 1 < ? THEN 'PENDING' ELSE 'FAILED' END,
                            next_attempt_at = TIMESTAMPADD(SECOND,
                                LEAST(?, ? * POWER(2, LEAST(failure_count, 20))), CURRENT_TIMESTAMP(6)),
                            failure_count = failure_count + 1, last_error_type = ?,
                            claim_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ? AND status = 'PUBLISHING' AND claim_token = ?
                            AND ((? AND lease_until <= CURRENT_TIMESTAMP(6))
                                OR (NOT ? AND lease_until > CURRENT_TIMESTAMP(6)))
                        """, settings.maxFailures(), settings.retryMaxSeconds(), settings.retryBaseSeconds(),
                errorType, job.id(), job.claimToken(), recovering, recovering);
    }

    public record Job(long id, String claimToken, MessageEnvelope envelope) {
    }
}
