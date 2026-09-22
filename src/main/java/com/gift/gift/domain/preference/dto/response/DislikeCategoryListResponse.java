package com.gift.gift.domain.preference.dto.response;

import java.util.List;

import com.gift.gift.domain.preference.support.PreferencePolicy;

public record DislikeCategoryListResponse(
        int maxSelectableCount,
        List<DislikeCategoryItemResponse> categories
) {

    public DislikeCategoryListResponse {
        categories = List.copyOf(categories);
    }

    public static DislikeCategoryListResponse from(
            List<DislikeCategoryItemResponse> categories
    ) {
        return new DislikeCategoryListResponse(
                PreferencePolicy.MAX_SELECTABLE_COUNT,
                categories
        );
    }
}
