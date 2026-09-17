package com.gift.gift.domain.product.repository;

import java.util.List;

public interface ProductQueryRepository {
    // 검색 조건, 조회 개수
    List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            int fetchCount
    );
}
