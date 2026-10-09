CREATE TABLE recipient_profiles
(
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id            BIGINT NOT NULL,
    profile_status          VARCHAR(20) NOT NULL DEFAULT 'NONE',
    source_version          BIGINT NOT NULL DEFAULT 0,
    analyzed_source_version BIGINT NOT NULL DEFAULT 0,
    last_changed_at         DATETIME(6) NULL,
    window_started_at       DATETIME(6) NULL,
    pending_since           DATETIME(6) NULL,
    retry_count             TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,

    CONSTRAINT pk_recipient_profiles
        PRIMARY KEY (id),

    CONSTRAINT uk_recipient_profiles_recipient
        UNIQUE (recipient_id),

    CONSTRAINT fk_recipient_profiles_recipient
        FOREIGN KEY (recipient_id)
            REFERENCES users (id),

    CONSTRAINT chk_recipient_profiles_status
        CHECK (
            profile_status IN (
                               'NONE',
                               'PENDING',
                               'COMPLETED',
                               'FAILED'
                )
            ),

    CONSTRAINT chk_recipient_profiles_source_version
        CHECK (source_version >= 0),

    CONSTRAINT chk_recipient_profiles_analyzed_version
        CHECK (analyzed_source_version >= 0),

    CONSTRAINT chk_recipient_profiles_version_order
        CHECK (analyzed_source_version <= source_version),

    CONSTRAINT chk_recipient_profiles_retry_count
        CHECK (retry_count BETWEEN 0 AND 2),

    INDEX idx_recipient_profiles_last_changed
        (profile_status, last_changed_at, id),

    INDEX idx_recipient_profiles_window_started
        (profile_status, window_started_at, id),

    INDEX idx_recipient_profiles_pending_timeout
        (profile_status, pending_since, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


CREATE TABLE recipient_recommended_products
(
    id             BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id   BIGINT NOT NULL,
    product_id     BIGINT NOT NULL,
    rank_order     TINYINT UNSIGNED NOT NULL,
    source_version BIGINT NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,

    CONSTRAINT pk_recipient_recommended_products
        PRIMARY KEY (id),

    CONSTRAINT uk_rec_products_recipient_version_product
        UNIQUE (recipient_id, source_version, product_id),

    CONSTRAINT uk_rec_products_recipient_version_rank
        UNIQUE (recipient_id, source_version, rank_order),

    CONSTRAINT fk_rec_products_recipient
        FOREIGN KEY (recipient_id)
            REFERENCES recipient_profiles (recipient_id),

    CONSTRAINT fk_rec_products_product
        FOREIGN KEY (product_id)
            REFERENCES products (id),

    CONSTRAINT chk_rec_products_rank_order
        CHECK (rank_order BETWEEN 1 AND 30),

    CONSTRAINT chk_rec_products_source_version
        CHECK (source_version >= 0),

    INDEX idx_rec_products_product
        (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
