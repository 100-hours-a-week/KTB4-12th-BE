package com.gift.gift.domain.product.repository;

import java.util.List;
// 상품 검색 조건 : 검색어 정리 및 카테고리 없으면 빈 목록 설정
public record ProductSearchCondition(
        String query,
        List<Long> categoryIds,
        ProductSort sort
) {

    public ProductSearchCondition {
        query = query == null ? "" : query.strip();

        List<Long> ids = categoryIds == null
                ? List.of()
                : List.copyOf(categoryIds);

        if (ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException(
                    "카테고리 ID는 양수여야 합니다."
            );
        }

        categoryIds = ids.stream()
                .distinct()
                .sorted()
                .toList();

        sort = sort == null ? ProductSort.POPULAR : sort;
    }

    public ProductSearchCondition(String query, List<Long> categoryIds) {
        this(query, categoryIds, ProductSort.POPULAR);
    }

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    public boolean hasCategoryIds() {
        return !categoryIds.isEmpty();
    }
}
