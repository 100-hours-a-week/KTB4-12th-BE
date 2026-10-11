CREATE TABLE payments (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          order_id VARCHAR(36) NOT NULL,
                          payment_key VARCHAR(36) NOT NULL,
                          request_hash CHAR(64) NOT NULL,
                          amount BIGINT NOT NULL,
                          status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                          approved_transaction_key VARCHAR(36) NULL,
                          failure_code VARCHAR(60) NULL,
                          approved_at DATETIME(6) NULL,
                          canceled_at DATETIME(6) NULL,
                          created_at DATETIME(6) NOT NULL,
                          updated_at DATETIME(6) NOT NULL,
                          CONSTRAINT pk_payments PRIMARY KEY (id),
                          CONSTRAINT uk_payments_order_id UNIQUE (order_id),
                          CONSTRAINT uk_payments_payment_key UNIQUE (payment_key),
                          CONSTRAINT uk_payments_approved_transaction_key UNIQUE (approved_transaction_key),
                          CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
                          CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED', 'CANCELED')),
                          CONSTRAINT chk_payments_failure_code CHECK (
                              failure_code IS NULL
                                  OR failure_code IN ('PAYMENT_DECLINED', 'TEMPORARY_PROCESSING_ERROR', 'INVALID_PAYMENT_AMOUNT')
                              ),
                          CONSTRAINT chk_payments_status_fields CHECK (
                              (
                                  status = 'PENDING'
                                      AND approved_transaction_key IS NULL
                                      AND failure_code IS NULL
                                      AND approved_at IS NULL
                                      AND canceled_at IS NULL
                                  )
                                  OR (
                                  status = 'COMPLETED'
                                      AND approved_transaction_key IS NOT NULL
                                      AND failure_code IS NULL
                                      AND approved_at IS NOT NULL
                                      AND canceled_at IS NULL
                                  )
                                  OR (
                                  status = 'DECLINED'
                                      AND approved_transaction_key IS NULL
                                      AND failure_code IS NOT NULL
                                      AND approved_at IS NULL
                                      AND canceled_at IS NULL
                                  )
                                  OR (
                                  status = 'CANCELED'
                                      AND approved_transaction_key IS NOT NULL
                                      AND failure_code IS NULL
                                      AND approved_at IS NOT NULL
                                      AND canceled_at IS NOT NULL
                                  )
                              )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment_cancels (
                                 id BIGINT NOT NULL AUTO_INCREMENT,
                                 payment_id BIGINT NOT NULL,
                                 idempotency_key VARCHAR(100) NOT NULL,
                                 request_hash CHAR(64) NOT NULL,
                                 transaction_key VARCHAR(36) NULL,
                                 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                 cancel_amount BIGINT NULL,
                                 failure_code VARCHAR(60) NULL,
                                 canceled_at DATETIME(6) NULL,
                                 created_at DATETIME(6) NOT NULL,
                                 updated_at DATETIME(6) NOT NULL,
                                 CONSTRAINT pk_payment_cancels PRIMARY KEY (id),
                                 CONSTRAINT uk_payment_cancels_payment_idempotency UNIQUE (payment_id, idempotency_key),
                                 CONSTRAINT uk_payment_cancels_transaction_key UNIQUE (transaction_key),
                                 CONSTRAINT chk_payment_cancels_status CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED')),
                                 CONSTRAINT chk_payment_cancels_amount_positive CHECK (cancel_amount IS NULL OR cancel_amount > 0),
                                 CONSTRAINT chk_payment_cancels_failure_code CHECK (
                                     failure_code IS NULL
                                         OR failure_code IN ('CANCELLATION_DECLINED', 'TEMPORARY_PROCESSING_ERROR')
                                     ),
                                 CONSTRAINT chk_payment_cancels_status_fields CHECK (
                                     (
                                         status = 'PENDING'
                                             AND transaction_key IS NULL
                                             AND cancel_amount IS NULL
                                             AND failure_code IS NULL
                                             AND canceled_at IS NULL
                                         )
                                         OR (
                                         status = 'COMPLETED'
                                             AND transaction_key IS NOT NULL
                                             AND cancel_amount IS NOT NULL
                                             AND failure_code IS NULL
                                             AND canceled_at IS NOT NULL
                                         )
                                         OR (
                                         status IN ('DECLINED', 'FAILED')
                                             AND transaction_key IS NULL
                                             AND cancel_amount IS NULL
                                             AND failure_code IS NOT NULL
                                             AND canceled_at IS NULL
                                         )
                                     ),
                                 CONSTRAINT fk_payment_cancels_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
                                 INDEX idx_payment_cancels_payment_created (payment_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
