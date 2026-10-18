CREATE TABLE product_view_stats
(
    product_id BIGINT NOT NULL,
    views INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_product_view_stats PRIMARY KEY (product_id),
    CONSTRAINT fk_product_view_stats_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Keep the legacy column for existing catalog INSERTs; runtime reads and writes use product_view_stats.
INSERT INTO product_view_stats (product_id, views, deleted_at, created_at, updated_at)
SELECT id, views, deleted_at, created_at, updated_at
FROM products;

CREATE TRIGGER products_create_view_stats
AFTER INSERT ON products
FOR EACH ROW
INSERT INTO product_view_stats (product_id, views, deleted_at, created_at, updated_at)
VALUES (NEW.id, NEW.views, NEW.deleted_at, NEW.created_at, NEW.updated_at);

DELIMITER $$
CREATE TRIGGER products_sync_view_stats_deletion
AFTER UPDATE ON products
FOR EACH ROW
BEGIN
    IF NOT (NEW.deleted_at <=> OLD.deleted_at) THEN
        UPDATE product_view_stats
        SET deleted_at = NEW.deleted_at,
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE product_id = NEW.id;
    END IF;
END$$
DELIMITER ;
