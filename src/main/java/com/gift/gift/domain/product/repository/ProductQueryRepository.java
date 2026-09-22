package com.gift.gift.domain.product.repository;

import java.util.List;

import com.gift.gift.domain.product.cursor.ProductCursor;

public interface ProductQueryRepository {
    // 검색 조건, 조회 개수
    default List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            int fetchCount
    ) {
        return searchProducts(condition, null, fetchCount);
    };

    List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            ProductCursor cursor,
            int fetchCount
    );
}
