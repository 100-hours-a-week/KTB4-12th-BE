package com.gift.gift.domain.product.cursor;

import java.time.LocalDateTime;
import java.util.List;

import com.gift.gift.domain.product.repository.ProductSort;

public record ProductCursor(
        int version,
        ProductSort sort,
        String query,
        List<Long> categoryIds,
        Long productId,
        LocalDateTime createdAt,
        Integer views,
        Integer sales,
        ProductSort requestedSort,
        Long recipientUserId,
        Long analyzedSourceVersion,
        Boolean recommendedRegion,
        Integer rankOrder
) {

    // 기존 v1 일반 상품 커서 생성자를 유지한다.
    public ProductCursor(
            int version,
            ProductSort sort,
            String query,
            List<Long> categoryIds,
            Long productId,
            LocalDateTime createdAt,
            Integer views,
            Integer sales
    ) {
        this(
                version,
                sort,
                query,
                categoryIds,
                productId,
                createdAt,
                views,
                sales,
                null,
                null,
                null,
                null,
                null
        );
    }
}
