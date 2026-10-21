-- product-catalog-20260922-v1 적재 준비
-- 기존 Flyway 이력을 변경하지 않고 공유 이미지 연결을 지원한다.

-- 같은 이미지 asset을 여러 상품이 공유할 수 있으므로 object_key 단독 UNIQUE를 제거한다.
-- 상품 내부의 이미지 순서는 기존 (product_id, sort_order) UNIQUE가 계속 보장한다.
ALTER TABLE product_images
    DROP INDEX uk_product_images_object_key;
