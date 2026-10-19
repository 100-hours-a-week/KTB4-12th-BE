ALTER TABLE products
    ADD COLUMN search_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_products_search_version
        CHECK (search_version >= 0);

-- 상품 물리 삭제 이후에도 동기화 작업을 보존하기 위해 상품 FK를 두지 않는다.
CREATE TABLE product_search_outbox
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT      NOT NULL,
    product_version BIGINT      NOT NULL,

    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_count   INT         NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    claim_token     VARCHAR(64) NULL,
    lease_until     DATETIME(6) NULL,

    processed_at    DATETIME(6) NULL,
    last_error_type VARCHAR(255) NULL,

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_product_search_outbox_version
        UNIQUE (product_id, product_version),

    CONSTRAINT chk_product_search_outbox_product
        CHECK (product_id > 0),

    CONSTRAINT chk_product_search_outbox_version
        CHECK (product_version > 0),

    CONSTRAINT chk_product_search_outbox_failure_count
        CHECK (failure_count >= 0),

    CONSTRAINT chk_product_search_outbox_status
        CHECK (
            status IN (
                       'PENDING',
                       'PROCESSING',
                       'PROCESSED',
                       'FAILED'
                )
            ),

    CONSTRAINT chk_product_search_outbox_processed
        CHECK (
            (status = 'PROCESSED' AND processed_at IS NOT NULL)
                OR
            (status <> 'PROCESSED' AND processed_at IS NULL)
            ),

    CONSTRAINT chk_product_search_outbox_claim
        CHECK (
            (
                status = 'PROCESSING'
                    AND claim_token IS NOT NULL
                    AND lease_until IS NOT NULL
                )
                OR
            (
                status <> 'PROCESSING'
                    AND claim_token IS NULL
                    AND lease_until IS NULL
                )
            ),

    INDEX           idx_product_search_outbox_due (
        status,
        next_attempt_at,
        id
    ),

    INDEX           idx_product_search_outbox_lease (
        status,
        lease_until,
        id
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
