package com.gift.gift.domain.product.repository;

import java.util.List;

import com.gift.gift.domain.product.cursor.ProductCursor;

public interface ProductQueryRepository {

    default List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            int fetchCount
    ) {
        return searchProducts(condition, null, fetchCount);
    }

    List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            ProductCursor cursor,
            int fetchCount
    );

    boolean hasMatchingRecommendedProducts(
            ProductSearchCondition condition
    );
}
