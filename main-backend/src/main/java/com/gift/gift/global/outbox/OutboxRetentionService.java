package com.gift.gift.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxRetentionService {
    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${app.outbox.cleanup-interval-ms:3600000}")
    @Transactional
    public int deleteCompletedEvents() {
        int reports = jdbc.update("""
                DELETE FROM outbox_event
                WHERE event_type = 'report.created.v1' AND status = 'PUBLISHED'
                  AND published_at < TIMESTAMPADD(DAY, -7, CURRENT_TIMESTAMP(6))
                ORDER BY published_at, id LIMIT 1000
                """);
        int notifications = jdbc.update("""
                DELETE FROM outbox_event
                WHERE event_type = 'notification.create.v1' AND status = 'PROCESSED'
                  AND processed_at < TIMESTAMPADD(DAY, -7, CURRENT_TIMESTAMP(6))
                ORDER BY processed_at, id LIMIT 1000
                """);
        return reports + notifications;
    }
}
