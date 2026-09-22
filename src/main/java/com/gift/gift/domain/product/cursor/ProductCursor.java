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
        Integer sales
) {
}
