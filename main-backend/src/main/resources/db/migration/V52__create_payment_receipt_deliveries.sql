CREATE TABLE payment_receipt_deliveries (
                                            receipt_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
                                            source_id BIGINT NOT NULL,
                                            payment_completed_at DATETIME(6) NOT NULL,
                                            status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
                                            auto_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
                                            claim_token CHAR(36) COLLATE utf8mb4_0900_bin NULL,
                                            lease_until DATETIME(6) NULL,
                                            accepted_at DATETIME(6) NULL,
                                            last_error_code VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
                                            replay_token CHAR(36) COLLATE utf8mb4_0900_bin NULL,
                                            replay_used_at DATETIME(6) NULL,
                                            created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                            updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                            PRIMARY KEY (receipt_type, source_id),

                                            CONSTRAINT chk_payment_receipt_deliveries_type
                                                CHECK (receipt_type IN ('PAYMENT', 'CANCELLATION')),
                                            CONSTRAINT chk_payment_receipt_deliveries_source
                                                CHECK (source_id > 0),
                                            CONSTRAINT chk_payment_receipt_deliveries_status
                                                CHECK (status IN ('READY', 'SENDING', 'SENT', 'SKIPPED', 'FAILED')),
                                            CONSTRAINT chk_payment_receipt_deliveries_attempts
                                                CHECK (auto_attempts BETWEEN 0 AND 4),
                                            CONSTRAINT chk_payment_receipt_deliveries_claim
                                                CHECK (
                                                    (status = 'SENDING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
                                                        OR
                                                    (status <> 'SENDING' AND claim_token IS NULL AND lease_until IS NULL)
                                                    ),
                                            CONSTRAINT chk_payment_receipt_deliveries_accepted
                                                CHECK (
                                                    (status = 'SENT' AND accepted_at IS NOT NULL)
                                                        OR
                                                    (status <> 'SENT' AND accepted_at IS NULL)
                                                    ),
                                            CONSTRAINT chk_payment_receipt_deliveries_replay
                                                CHECK (replay_used_at IS NULL OR replay_token IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin;
