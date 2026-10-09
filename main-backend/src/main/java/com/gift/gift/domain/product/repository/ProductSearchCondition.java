package com.gift.gift.domain.product.repository;

import java.util.List;

public record ProductSearchCondition(
        String query,
        List<Long> categoryIds,
        ProductSort sort,
        ProductSort requestedSort,
        Long recipientUserId,
        Long analyzedSourceVersion
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
        requestedSort = requestedSort == null ? sort : requestedSort;

        if (recipientUserId != null && recipientUserId <= 0) {
            throw new IllegalArgumentException(
                    "수신자 ID는 양수여야 합니다."
            );
        }

        if (requestedSort == ProductSort.AI_RECOMMENDED) {
            if (recipientUserId == null
                    || analyzedSourceVersion == null
                    || analyzedSourceVersion < 0) {
                throw new IllegalArgumentException(
                        "AI 추천 조회에는 수신자와 분석 버전이 필요합니다."
                );
            }

            if (sort != ProductSort.AI_RECOMMENDED
                    && sort != ProductSort.POPULAR) {
                throw new IllegalArgumentException(
                        "AI 추천 조회는 추천순 또는 인기순으로 적용해야 합니다."
                );
            }
        } else {
            if (sort != requestedSort || analyzedSourceVersion != null) {
                throw new IllegalArgumentException(
                        "일반 정렬 조회 조건이 올바르지 않습니다."
                );
            }
        }
    }

    // 기존 일반 조회 호출부를 유지한다.
    public ProductSearchCondition(
            String query,
            List<Long> categoryIds,
            ProductSort sort
    ) {
        this(query, categoryIds, sort, sort, null, null);
    }

    public ProductSearchCondition(
            String query,
            List<Long> categoryIds
    ) {
        this(query, categoryIds, ProductSort.POPULAR);
    }

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    public boolean hasCategoryIds() {
        return !categoryIds.isEmpty();
    }

    public boolean isAiRequested() {
        return requestedSort == ProductSort.AI_RECOMMENDED;
    }

    public ProductSearchCondition withAppliedSort(
            ProductSort appliedSort
    ) {
        return new ProductSearchCondition(
                query,
                categoryIds,
                appliedSort,
                requestedSort,
                recipientUserId,
                analyzedSourceVersion
        );
    }
}
