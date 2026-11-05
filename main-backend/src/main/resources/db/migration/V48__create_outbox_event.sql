CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    event_type VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version INT NOT NULL,
    deduplication_key VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_type VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_version BIGINT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    failure_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    claim_token VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    lease_until DATETIME(6) NULL,
    last_error_type VARCHAR(255) NULL,
    trace_id VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    CONSTRAINT uk_outbox_event_dedup UNIQUE (event_type, deduplication_key),
    CONSTRAINT chk_outbox_event_numbers CHECK (
        schema_version >= 1 AND failure_count >= 0
        AND (aggregate_version IS NULL OR aggregate_version > 0)
    ),
    CONSTRAINT chk_outbox_event_payload CHECK (JSON_TYPE(payload) = 'OBJECT'),
    CONSTRAINT chk_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_outbox_event_claim CHECK (
        (status = 'PUBLISHING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PUBLISHING' AND claim_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT chk_outbox_event_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    INDEX idx_outbox_event_due (event_type, status, next_attempt_at, id),
    INDEX idx_outbox_event_lease (event_type, status, lease_until, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
