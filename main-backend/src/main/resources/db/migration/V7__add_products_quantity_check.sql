ALTER TABLE products
    ADD CONSTRAINT chk_products_quantity_non_negative
        CHECK (quantity >= 0);
