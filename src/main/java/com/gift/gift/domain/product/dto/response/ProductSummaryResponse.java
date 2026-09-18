package com.gift.gift.domain.product.dto.response;

import java.math.BigDecimal;

import com.gift.gift.domain.product.repository.ProductSummaryProjection;

public record ProductSummaryResponse(
        Long productId,
        String brandName,
        String productName,
        BigDecimal price,
        String thumbnailUrl
) {

    public static ProductSummaryResponse from(
            ProductSummaryProjection product,
            String thumbnailUrl
    ) {
        return new ProductSummaryResponse(
                product.productId(),
                product.brandName(),
                product.productName(),
                product.price(),
                thumbnailUrl
        );
    }
}
