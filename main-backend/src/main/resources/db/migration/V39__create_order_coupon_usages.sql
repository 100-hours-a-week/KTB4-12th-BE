CREATE TABLE order_coupon_usages
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    order_record_id     BIGINT      NOT NULL,
    coupon_inventory_id BIGINT      NOT NULL,
    status              VARCHAR(20) NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,

    CONSTRAINT pk_order_coupon_usages
        PRIMARY KEY (id),

    CONSTRAINT uk_order_coupon_usages_order
        UNIQUE (order_record_id),

    CONSTRAINT fk_order_coupon_usages_order
        FOREIGN KEY (order_record_id)
            REFERENCES orders (id),

    CONSTRAINT fk_order_coupon_usages_inventory
        FOREIGN KEY (coupon_inventory_id)
            REFERENCES coupon_inventories (id),

    CONSTRAINT chk_order_coupon_usages_status
        CHECK (status IN ('RESERVED', 'USED', 'RESTORED'))
);
