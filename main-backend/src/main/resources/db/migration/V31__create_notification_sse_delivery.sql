CREATE TABLE notification_sse_connections (
    id CHAR(36) NOT NULL PRIMARY KEY,
    node_id CHAR(36) NOT NULL,
    recipient_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_sse_connection_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_sse_connection_recipient (recipient_id, expires_at),
    INDEX idx_sse_connection_node (node_id, expires_at),
    INDEX idx_sse_connection_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE notification_sse_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    notification_id BIGINT NOT NULL,
    connection_id CHAR(36) NOT NULL,
    node_id CHAR(36) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    acknowledged_at DATETIME(6) NULL,
    CONSTRAINT uk_sse_delivery UNIQUE (notification_id, connection_id),
    CONSTRAINT fk_sse_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    CONSTRAINT fk_sse_delivery_connection FOREIGN KEY (connection_id) REFERENCES notification_sse_connections(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_sse_delivery_attempts CHECK (attempt_count >= 0),
    INDEX idx_sse_delivery_due (node_id, acknowledged_at, next_attempt_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
