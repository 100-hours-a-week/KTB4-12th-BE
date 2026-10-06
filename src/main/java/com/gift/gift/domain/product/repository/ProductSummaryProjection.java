package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductSummaryProjection(
        Long productId,
        String productName,
        String brandName,
        BigDecimal price,
        Integer views,
        Integer sales,
        LocalDateTime createdAt,
        Integer rankOrder
) {

    // 기존 일반 상품 조회와 테스트의 생성자를 유지한다.
    public ProductSummaryProjection(
            Long productId,
            String productName,
            String brandName,
            BigDecimal price,
            Integer views,
            Integer sales,
            LocalDateTime createdAt
    ) {
        this(
                productId,
                productName,
                brandName,
                price,
                views,
                sales,
                createdAt,
                null
        );
    }
}
