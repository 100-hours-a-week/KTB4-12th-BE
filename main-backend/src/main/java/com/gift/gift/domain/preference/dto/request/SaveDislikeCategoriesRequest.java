package com.gift.gift.domain.preference.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.gift.gift.domain.preference.validation.UniqueCategoryIds;
import com.gift.gift.global.exception.ValidationErrorReason;

public record SaveDislikeCategoriesRequest(
        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
        @UniqueCategoryIds(
                message = ValidationErrorReason.Message.DUPLICATE_CATEGORY_ID
        )
        List<
                        @NotNull(message = ValidationErrorReason.Message.REQUIRED)
                        @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
                                Long
                        > categoryIds
) {
}
