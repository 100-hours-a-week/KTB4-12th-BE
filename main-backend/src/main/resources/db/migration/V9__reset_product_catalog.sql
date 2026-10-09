-- 기존 시연 카탈로그를 product-catalog-20260922-v1으로 교체하기 위한 초기화
-- FK 자식에서 부모 순서로 삭제한다.

DELETE FROM gift_histories;
DELETE FROM product_views;
DELETE FROM product_images;
DELETE FROM products;
DELETE FROM user_dislike_categories;
-- categories는 자기참조 FK를 사용하므로 자식부터 삭제한다.
DELETE FROM categories WHERE parent_id IS NOT NULL;
DELETE FROM categories WHERE parent_id IS NULL;

ALTER TABLE gift_histories AUTO_INCREMENT = 1;
ALTER TABLE product_images AUTO_INCREMENT = 1;
ALTER TABLE products AUTO_INCREMENT = 1;
ALTER TABLE user_dislike_categories AUTO_INCREMENT = 1;
ALTER TABLE categories AUTO_INCREMENT = 1;
