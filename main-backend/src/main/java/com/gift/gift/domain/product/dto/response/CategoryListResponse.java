package com.gift.gift.domain.product.dto.response;

import java.util.List;

public record CategoryListResponse(
        List<CategoryResponse> categories
) {

    public CategoryListResponse {
        categories = List.copyOf(categories);
    }

    public static CategoryListResponse from(
            List<CategoryResponse> categories
    ) {
        return new CategoryListResponse(categories);
    }
}
