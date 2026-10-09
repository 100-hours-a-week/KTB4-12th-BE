CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    reference_type VARCHAR(50) NULL,
    reference_id BIGINT NULL,
    title VARCHAR(100) NOT NULL,
    message VARCHAR(255) NOT NULL,
    deduplication_key VARCHAR(120) NOT NULL,
    read_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uk_notifications_deduplication_key UNIQUE (deduplication_key),
    CONSTRAINT chk_notifications_reference_pair CHECK (
        (reference_type IS NULL AND reference_id IS NULL)
        OR (reference_type IS NOT NULL AND reference_id IS NOT NULL)
    ),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    INDEX idx_notifications_recipient_created (recipient_id, created_at DESC, id DESC),
    INDEX idx_notifications_recipient_read (recipient_id, read_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
