ALTER TABLE outbox_event
    ADD COLUMN processed_at DATETIME(6) NULL AFTER published_at,
    DROP CHECK chk_outbox_event_status,
    ADD CONSTRAINT chk_outbox_event_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'PROCESSED', 'FAILED')
    ),
    ADD CONSTRAINT chk_outbox_event_processed CHECK (
        (status = 'PROCESSED' AND processed_at IS NOT NULL)
        OR (status <> 'PROCESSED' AND processed_at IS NULL)
    ),
    ADD INDEX idx_outbox_event_published (event_type, status, published_at, id),
    ADD INDEX idx_outbox_event_processed (event_type, status, processed_at, id);
