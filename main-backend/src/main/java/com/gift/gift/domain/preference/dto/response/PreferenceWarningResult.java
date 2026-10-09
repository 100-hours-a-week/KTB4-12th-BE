package com.gift.gift.domain.preference.dto.response;

import com.gift.gift.domain.product.entity.Category;

public record PreferenceWarningResult(
        Long categoryId,
        String categoryName
) {

    public static PreferenceWarningResult from(Category category) {
        return new PreferenceWarningResult(
                category.getId(),
                category.getName()
        );
    }
}
