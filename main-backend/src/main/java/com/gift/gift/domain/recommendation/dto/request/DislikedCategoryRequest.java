package com.gift.gift.domain.recommendation.dto.request;

import java.util.Objects;

import com.gift.gift.domain.product.entity.Category;

public record DislikedCategoryRequest(
        Long categoryId,
        String categoryName
) {

    public DislikedCategoryRequest {
        Objects.requireNonNull(
                categoryId,
                "카테고리 ID는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                categoryName,
                "카테고리 이름은 null일 수 없습니다."
        );

        if (categoryId <= 0) {
            throw new IllegalArgumentException(
                    "카테고리 ID는 양수여야 합니다."
            );
        }

        if (categoryName.isBlank()) {
            throw new IllegalArgumentException(
                    "카테고리 이름은 비어 있을 수 없습니다."
            );
        }
    }

    public static DislikedCategoryRequest from(Category category) {
        Objects.requireNonNull(
                category,
                "카테고리는 null일 수 없습니다."
        );

        return new DislikedCategoryRequest(
                category.getId(),
                category.getName()
        );
    }
}
