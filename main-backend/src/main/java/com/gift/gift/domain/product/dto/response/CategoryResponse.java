package com.gift.gift.domain.product.dto.response;

import java.util.List;

import com.gift.gift.domain.product.entity.Category;

public record CategoryResponse(
        Long categoryId,
        String name,
        List<ChildCategoryResponse> children
) {

    public CategoryResponse {
        children = List.copyOf(children);
    }

    public static CategoryResponse from(
            Category category,
            List<ChildCategoryResponse> children
    ) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                children
        );
    }

    public record ChildCategoryResponse(
            Long categoryId,
            String name
    ) {

        public static ChildCategoryResponse from(Category category) {
            return new ChildCategoryResponse(
                    category.getId(),
                    category.getName()
            );
        }
    }
}
