package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 조회 결과
public record ProductSummaryProjection(
        Long productId,
        String productName,
        String brandName,
        BigDecimal price,
        Integer views,
        Integer sales,
        LocalDateTime createdAt
) {
}
