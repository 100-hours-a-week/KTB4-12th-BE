package com.gift.gift.domain.bugreport.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BugReportReceiptRepository {
    private final JdbcTemplate jdbc;

    public boolean exists(String reportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM outbox_event
                WHERE event_type = 'report.created.v1' AND deduplication_key = ?)
            """, Boolean.class, reportId));
    }
}
