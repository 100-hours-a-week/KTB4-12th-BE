CREATE TABLE product_views
(
    id             BIGINT NOT NULL AUTO_INCREMENT,
    user_id        BIGINT NOT NULL,
    product_id     BIGINT NOT NULL,
    last_viewed_at DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL,

    CONSTRAINT pk_product_views PRIMARY KEY (id),

    CONSTRAINT uk_product_views_user_product
        UNIQUE (user_id, product_id),

    CONSTRAINT fk_product_views_user
        FOREIGN KEY (user_id)
            REFERENCES users (id),

    CONSTRAINT fk_product_views_product
        FOREIGN KEY (product_id)
            REFERENCES products (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
