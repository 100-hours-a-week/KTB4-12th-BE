CREATE TABLE notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    source_identifier VARCHAR(120) NOT NULL,
    type VARCHAR(50) NOT NULL,
    reference_type VARCHAR(50) NULL,
    reference_id BIGINT NULL,
    title VARCHAR(100) NOT NULL,
    message VARCHAR(255) NOT NULL,
    deduplication_key VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    last_error_type VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_notification_outbox_deduplication_key UNIQUE (deduplication_key),
    CONSTRAINT fk_notification_outbox_recipient FOREIGN KEY (recipient_id) REFERENCES users(id),
    CONSTRAINT chk_notification_outbox_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT chk_notification_outbox_failure_count CHECK (failure_count >= 0),
    CONSTRAINT chk_notification_outbox_processed CHECK (
        (status = 'PROCESSED' AND processed_at IS NOT NULL)
        OR (status <> 'PROCESSED' AND processed_at IS NULL)),
    CONSTRAINT chk_notification_outbox_reference CHECK (
        (reference_type IS NULL AND reference_id IS NULL)
        OR (reference_type IS NOT NULL AND reference_id IS NOT NULL)),
    INDEX idx_notification_outbox_due (status, next_attempt_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
