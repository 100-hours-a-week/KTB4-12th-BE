ALTER TABLE orders
    DROP CHECK chk_orders_failure_code;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_failure_code CHECK (
        failure_code IS NULL
        OR failure_code IN (
            'PAYMENT_DECLINED',
            'PAYMENT_RESULT_UNCONFIRMED',
            'PAYMENT_SERVER_UNAVAILABLE',
            'LOCAL_PROCESSING_FAILED',
            'PAYMENT_RECONCILIATION_REQUIRED'
        )
    );

CREATE INDEX idx_orders_reconciliation
    ON orders (status, approval_result, updated_at, id);
