CREATE TABLE payment_recovery_tasks (
                                        id BIGINT NOT NULL AUTO_INCREMENT,
                                        target_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
                                        target_id BIGINT NOT NULL,
                                        status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
                                        generation BIGINT NOT NULL DEFAULT 0,
                                        started_at DATETIME(6) NULL,
                                        deadline_at DATETIME(6) NULL,
                                        next_run_at DATETIME(6) NULL,
                                        reconciliation_attempts INT UNSIGNED NOT NULL DEFAULT 0,
                                        cancel_post_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
                                        claim_token CHAR(36) COLLATE utf8mb4_0900_bin NULL,
                                        lease_until DATETIME(6) NULL,
                                        last_error_code VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
                                        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                        PRIMARY KEY (id),
                                        CONSTRAINT uk_payment_recovery_tasks_target
                                            UNIQUE (target_type, target_id),
                                        CONSTRAINT chk_payment_recovery_tasks_target_type
                                            CHECK (target_type IN ('APPROVAL', 'CANCELLATION')),
                                        CONSTRAINT chk_payment_recovery_tasks_target_id
                                            CHECK (target_id > 0),
                                        CONSTRAINT chk_payment_recovery_tasks_status
                                            CHECK (status IN (
                                                              'WAITING_FOR_SYNC',
                                                              'READY',
                                                              'DISPATCHED',
                                                              'RUNNING',
                                                              'COMPLETED',
                                                              'REVIEW_REQUIRED'
                                                )),
                                        CONSTRAINT chk_payment_recovery_tasks_generation
                                            CHECK (generation >= 0),
                                        CONSTRAINT chk_payment_recovery_tasks_cancel_attempts
                                            CHECK (
                                                cancel_post_attempts <= 3
                                                    AND (target_type = 'CANCELLATION' OR cancel_post_attempts = 0)
                                                ),
                                        CONSTRAINT chk_payment_recovery_tasks_claim
                                            CHECK (
                                                (
                                                    status IN ('WAITING_FOR_SYNC', 'DISPATCHED', 'RUNNING')
                                                        AND claim_token IS NOT NULL
                                                        AND lease_until IS NOT NULL
                                                    )
                                                    OR
                                                (
                                                    status NOT IN ('WAITING_FOR_SYNC', 'DISPATCHED', 'RUNNING')
                                                        AND claim_token IS NULL
                                                        AND lease_until IS NULL
                                                    )
                                                ),
                                        CONSTRAINT chk_payment_recovery_tasks_ready_schedule
                                            CHECK (status <> 'READY' OR next_run_at IS NOT NULL),
                                        CONSTRAINT chk_payment_recovery_tasks_deadline
                                            CHECK (
                                                (started_at IS NULL AND deadline_at IS NULL)
                                                    OR (started_at IS NOT NULL AND deadline_at IS NOT NULL)
                                                ),
                                        INDEX idx_payment_recovery_tasks_due (status, next_run_at, id),
                                        INDEX idx_payment_recovery_tasks_lease (status, lease_until, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_bin;
