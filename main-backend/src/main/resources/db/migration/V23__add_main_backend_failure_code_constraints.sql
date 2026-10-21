ALTER TABLE orders
    ADD CONSTRAINT chk_orders_failure_code CHECK (
        failure_code IS NULL
        OR failure_code IN (
            'PAYMENT_DECLINED',
            'PAYMENT_RESULT_UNCONFIRMED',
            'PAYMENT_SERVER_UNAVAILABLE',
            'LOCAL_PROCESSING_FAILED'
        )
    );

ALTER TABLE payment_cancellations
    ADD CONSTRAINT chk_payment_cancellations_failure_code CHECK (
        failure_code IS NULL
        OR failure_code IN (
            'CANCELLATION_DECLINED',
            'CANCELLATION_RESULT_UNCONFIRMED',
            'PAYMENT_SERVER_UNAVAILABLE',
            'LOCAL_COMPENSATION_FAILED'
        )
    );
