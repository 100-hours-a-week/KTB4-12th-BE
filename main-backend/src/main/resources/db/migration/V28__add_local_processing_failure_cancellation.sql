ALTER TABLE payment_cancellations
    DROP CHECK chk_payment_cancellations_source;

ALTER TABLE payment_cancellations
    ADD CONSTRAINT chk_payment_cancellations_source CHECK (
        cancellation_source IN (
            'USER',
            'GIFT_EXPIRATION',
            'LOCAL_PROCESSING_FAILURE'
        )
    );

ALTER TABLE payment_cancellations
    DROP CHECK chk_payment_cancellations_failure_code;

ALTER TABLE payment_cancellations
    ADD CONSTRAINT chk_payment_cancellations_failure_code CHECK (
        failure_code IS NULL
        OR failure_code IN (
            'CANCELLATION_DECLINED',
            'CANCELLATION_RESULT_UNCONFIRMED',
            'PAYMENT_SERVER_UNAVAILABLE',
            'LOCAL_COMPENSATION_FAILED',
            'CANCELLATION_RECONCILIATION_REQUIRED'
        )
    );

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
            'PAYMENT_RECONCILIATION_REQUIRED',
            'AUTO_CANCELLATION_PROCESSING',
            'AUTO_CANCELLATION_RECONCILIATION_REQUIRED'
        )
    );
