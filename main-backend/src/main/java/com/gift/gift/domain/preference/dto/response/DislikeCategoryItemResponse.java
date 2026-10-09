package com.gift.gift.domain.preference.dto.response;

import com.gift.gift.domain.product.entity.Category;

public record DislikeCategoryItemResponse(
        Long categoryId,
        String name,
        boolean isSelected
) {

    public static DislikeCategoryItemResponse from(
            Category category,
            boolean isSelected
    ) {
        return new DislikeCategoryItemResponse(
                category.getId(),
                category.getName(),
                isSelected
        );
    }
}
