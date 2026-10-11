CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id VARCHAR(36) NOT NULL,
    payment_key VARCHAR(36) NULL,
    payer_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    approval_result VARCHAR(20) NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    approved_transaction_key VARCHAR(36) NULL,
    failure_code VARCHAR(60) NULL,
    approved_at DATETIME(6) NULL,
    canceled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_id UNIQUE (order_id),
    CONSTRAINT uk_orders_payment_key UNIQUE (payment_key),
    CONSTRAINT uk_orders_payer_idempotency UNIQUE (payer_id, idempotency_key),
    CONSTRAINT uk_orders_approved_transaction_key UNIQUE (approved_transaction_key),
    CONSTRAINT chk_orders_quantity CHECK (quantity BETWEEN 1 AND 10),
    CONSTRAINT chk_orders_unit_price_non_negative CHECK (unit_price >= 0),
    CONSTRAINT chk_orders_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT chk_orders_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED', 'CANCELED')
    ),
    CONSTRAINT chk_orders_approval_result CHECK (
        approval_result IS NULL
        OR approval_result IN ('APPROVED', 'DECLINED', 'UNKNOWN')
    ),
    CONSTRAINT fk_orders_payer FOREIGN KEY (payer_id) REFERENCES users (id),
    CONSTRAINT fk_orders_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT fk_orders_product FOREIGN KEY (product_id) REFERENCES products (id),
    INDEX idx_orders_payer_created (payer_id, created_at, id),
    INDEX idx_orders_status_updated (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment_cancellations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_record_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    cancellation_source VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    cancel_transaction_key VARCHAR(36) NULL,
    cancel_amount BIGINT NULL,
    failure_code VARCHAR(60) NULL,
    canceled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_payment_cancellations PRIMARY KEY (id),
    CONSTRAINT uk_payment_cancellations_requester_idempotency
        UNIQUE (requester_id, idempotency_key),
    CONSTRAINT uk_payment_cancellations_transaction_key
        UNIQUE (cancel_transaction_key),
    CONSTRAINT chk_payment_cancellations_source CHECK (
        cancellation_source IN ('USER', 'GIFT_EXPIRATION')
    ),
    CONSTRAINT chk_payment_cancellations_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED')
    ),
    CONSTRAINT chk_payment_cancellations_amount_non_negative CHECK (
        cancel_amount IS NULL OR cancel_amount >= 0
    ),
    CONSTRAINT fk_payment_cancellations_order
        FOREIGN KEY (order_record_id) REFERENCES orders (id),
    CONSTRAINT fk_payment_cancellations_requester
        FOREIGN KEY (requester_id) REFERENCES users (id),
    INDEX idx_payment_cancellations_order (order_record_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE credit_transactions
    ADD CONSTRAINT fk_credit_transactions_order_record
        FOREIGN KEY (order_record_id) REFERENCES orders (id);
