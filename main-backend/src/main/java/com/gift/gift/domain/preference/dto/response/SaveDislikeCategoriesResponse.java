package com.gift.gift.domain.preference.dto.response;

import java.util.List;

public record SaveDislikeCategoriesResponse(
        List<Long> selectedCategoryIds
) {

    public SaveDislikeCategoriesResponse {
        selectedCategoryIds = List.copyOf(selectedCategoryIds);
    }

    public static SaveDislikeCategoriesResponse from(
            List<Long> selectedCategoryIds
    ) {
        return new SaveDislikeCategoriesResponse(selectedCategoryIds);
    }
}
