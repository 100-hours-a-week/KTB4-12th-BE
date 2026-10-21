ALTER TABLE gift_histories
    DROP CHECK chk_gift_histories_status;

ALTER TABLE gift_histories
    ADD COLUMN order_record_id BIGINT NULL AFTER id,
    ADD COLUMN expires_at DATETIME(6) NULL AFTER completed_at,
    ADD COLUMN canceled_at DATETIME(6) NULL AFTER expires_at,
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    MODIFY COLUMN completed_at DATETIME(6) NULL;

ALTER TABLE gift_histories
    ADD CONSTRAINT uk_gift_histories_order_record UNIQUE (order_record_id),
    ADD CONSTRAINT fk_gift_histories_order_record FOREIGN KEY (order_record_id) REFERENCES orders (id),
    ADD CONSTRAINT chk_gift_histories_status
        CHECK (
            status IN (
                       'PROCESSING',
                       'WAITING_FOR_ACCEPTANCE',
                       'COMPLETED',
                       'FAILED',
                       'CANCELED'
                )
            ),
    ADD CONSTRAINT chk_gift_histories_status_times
        CHECK (
            status IN ('PROCESSING', 'FAILED')
                OR (
                status = 'WAITING_FOR_ACCEPTANCE'
                    AND expires_at IS NOT NULL
                    AND completed_at IS NULL
                    AND canceled_at IS NULL
                )
                OR (
                status = 'COMPLETED'
                    AND completed_at IS NOT NULL
                    AND canceled_at IS NULL
                )
                OR (
                status = 'CANCELED'
                    AND completed_at IS NULL
                    AND canceled_at IS NOT NULL
                )
            ),
    ADD INDEX idx_gifts_recipient_status (recipient_id, status, created_at, id),
    ADD INDEX idx_gifts_sender_status (sender_id, status, created_at, id),
    ADD INDEX idx_gifts_expiration (status, expires_at, id);
