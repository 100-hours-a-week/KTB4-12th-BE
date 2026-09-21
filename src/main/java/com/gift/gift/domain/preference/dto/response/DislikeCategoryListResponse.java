package com.gift.gift.domain.preference.dto.response;

import java.util.List;

public record DislikeCategoryListResponse(
        int maxSelectableCount,
        List<DislikeCategoryItemResponse> categories
) {

    private static final int MAX_SELECTABLE_COUNT = 5;

    public DislikeCategoryListResponse {
        categories = List.copyOf(categories);
    }

    public static DislikeCategoryListResponse from(
            List<DislikeCategoryItemResponse> categories
    ) {
        return new DislikeCategoryListResponse(
                MAX_SELECTABLE_COUNT,
                categories
        );
    }
}
