DROP TABLE product_view_events;
DROP TABLE product_views;

DROP TRIGGER products_create_view_stats;

CREATE TRIGGER products_create_view_stats
AFTER INSERT ON products
FOR EACH ROW
INSERT INTO product_view_stats (product_id, views, deleted_at, created_at, updated_at)
VALUES (NEW.id, 0, NEW.deleted_at, NEW.created_at, NEW.updated_at);

ALTER TABLE products DROP COLUMN views;
