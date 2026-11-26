package com.gift.gift.domain.product.cache;

public final class ProductCacheKeys {

    private static final String DETAIL_PREFIX = "product:detail:v1:";
    private static final String RECENT_VIEW_PREFIX = "gift:product:view:recent:";
    private static final String PENDING_VIEWS = "gift:product:view:pending";
    private static final String SYNCING_VIEWS = "gift:product:view:syncing";

    private ProductCacheKeys() {
    }

    public static String detail(Long productId) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }

        return DETAIL_PREFIX + productId;
    }

    public static String recentView(Long userId, Long productId) {
        requirePositive(userId, "사용자 ID는 양수여야 합니다.");
        requirePositive(productId, "상품 ID는 양수여야 합니다.");

        return RECENT_VIEW_PREFIX + userId + ":" + productId;
    }

    public static String pendingViews() {
        return PENDING_VIEWS;
    }

    public static String syncingViews() {
        return SYNCING_VIEWS;
    }

    private static void requirePositive(Long value, String message) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
    }
}
