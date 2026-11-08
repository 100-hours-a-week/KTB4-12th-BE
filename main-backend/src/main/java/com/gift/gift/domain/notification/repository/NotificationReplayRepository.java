package com.gift.gift.domain.notification.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationReplayRepository {
    private final JdbcTemplate jdbc;

    public Optional<FailedJob> findFailed(long id, boolean legacy) {
        String table = legacy ? "notification_outbox" : "outbox_event";
        String type = legacy ? "" : " AND event_type='notification.create.v1'";
        return jdbc.query("SELECT id, failure_count, updated_at FROM " + table
                + " WHERE id=? AND status='FAILED'" + type,
            (rs, row) -> new FailedJob(rs.getLong("id"), rs.getInt("failure_count"),
                rs.getObject("updated_at", LocalDateTime.class), legacy), id).stream().findFirst();
    }

    public boolean requeueIfUnchanged(FailedJob job) {
        String table = job.legacy() ? "notification_outbox" : "outbox_event";
        String type = job.legacy() ? "" : " AND event_type='notification.create.v1'";
        return jdbc.update("UPDATE " + table + " SET status='PENDING', failure_count=0,"
                + " next_attempt_at=CURRENT_TIMESTAMP(6), last_error_type=NULL, updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE id=? AND status='FAILED' AND failure_count=? AND updated_at=?" + type,
            job.id(), job.failureCount(), job.updatedAt()) == 1;
    }

    public record FailedJob(long id, int failureCount, LocalDateTime updatedAt, boolean legacy) { }
}
