CREATE TABLE coupon_inventories
(
    id         BIGINT NOT NULL AUTO_INCREMENT,
    user_id    BIGINT NOT NULL,
    multiplier INT    NOT NULL,
    quantity   INT    NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_coupon_inventories PRIMARY KEY (id),
    CONSTRAINT uk_coupon_inventories_user_multiplier
        UNIQUE (user_id, multiplier),
    CONSTRAINT fk_coupon_inventories_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_coupon_inventories_multiplier
        CHECK (multiplier IN (2, 3)),
    CONSTRAINT chk_coupon_inventories_quantity
        CHECK (quantity >= 0)
);
