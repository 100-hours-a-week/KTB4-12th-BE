package com.gift.gift.domain.product.repository;

import java.util.List;
// 상품 검색 조건 : 검색어 정리 및 카테고리 없으면 빈 목록 설정
public record ProductSearchCondition(
        String query,
        List<Long> categoryIds
) {

    public ProductSearchCondition {
        query = query == null ? "" : query.strip();
        categoryIds = categoryIds == null
                ? List.of()
                : List.copyOf(categoryIds);
    }

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    public boolean hasCategoryIds() {
        return !categoryIds.isEmpty();
    }
}
