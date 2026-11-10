ALTER TABLE orders
    ADD COLUMN reconciliation_started_at DATETIME(6) NULL,
    ADD COLUMN next_reconciliation_at DATETIME(6) NULL,
    ADD COLUMN reconciliation_attempts INT NOT NULL DEFAULT 0;

ALTER TABLE payment_cancellations
    ADD COLUMN reconciliation_started_at DATETIME(6) NULL,
    ADD COLUMN next_reconciliation_at DATETIME(6) NULL,
    ADD COLUMN reconciliation_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_cancel_post_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX idx_orders_reconciliation_next_run
    ON orders (status, approval_result, next_reconciliation_at, id);

CREATE INDEX idx_payment_cancellations_reconciliation_next_run
    ON payment_cancellations (
                              cancellation_source,
                              status,
                              next_reconciliation_at,
                              id
        );
