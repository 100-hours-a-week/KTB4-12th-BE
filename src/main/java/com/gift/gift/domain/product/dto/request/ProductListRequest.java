package com.gift.gift.domain.product.dto.request;

import java.util.List;

import com.gift.gift.domain.product.repository.ProductSort;

public record ProductListRequest(
        String query,
        List<Long> categoryIds,
        ProductSort sort,
        Long recipientUserId,
        String cursor
) {
}
