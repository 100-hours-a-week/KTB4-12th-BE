package com.gift.gift.domain.product.query;

import java.util.List;

import com.gift.gift.domain.product.repository.ProductSummaryProjection;

public record ProductPage(
        List<ProductSummaryProjection> items,
        boolean hasNext,
        String nextCursor
) {

    public ProductPage {
        items = List.copyOf(items);
    }
}
