package com.gift.gift.domain.product.cache;

public final class ProductCacheKeys {

    private static final String DETAIL_PREFIX = "product:detail:v1:";

    private ProductCacheKeys() {
    }

    public static String detail(Long productId) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }

        return DETAIL_PREFIX + productId;
    }
}
