CREATE TABLE product_view_events
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    processed_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_product_view_events PRIMARY KEY (id),

    CONSTRAINT fk_product_view_events_product
        FOREIGN KEY (product_id)
            REFERENCES products (id),

    CONSTRAINT fk_product_view_events_user
        FOREIGN KEY (user_id)
            REFERENCES users (id),

    INDEX idx_product_view_events_pending_id (processed_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
