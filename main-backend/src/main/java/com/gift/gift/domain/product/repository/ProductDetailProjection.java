package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;

public record ProductDetailProjection(
        Long productId,
        String brand,
        String name,
        String description,
        BigDecimal price,
        Integer quantity,
        Long imageId,
        String objectKey,
        Integer sortOrder
) {}
