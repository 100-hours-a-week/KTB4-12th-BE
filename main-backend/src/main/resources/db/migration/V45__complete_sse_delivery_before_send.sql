ALTER TABLE notification_sse_deliveries
    RENAME COLUMN acknowledged_at TO completed_at;
