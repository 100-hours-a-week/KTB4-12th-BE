package com.gift.gift.domain.product.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.gift.gift.domain.product.repository.ProductSort;

public record ProductListRequest(
        String query,

        @Size(min = 1)
        List<@NotNull @Positive Long> categoryIds,

        ProductSort sort,

        @Positive
        Long recipientUserId,

        String cursor
) {
}
