package com.gift.gift.domain.product.dto.response;

import com.gift.gift.domain.product.entity.ProductImage;

public record ProductImageResponse(
        Long imageId,
        String imageUrl,
        Integer displayOrder
) {

    public static ProductImageResponse from(
            ProductImage image,
            String imageUrl
    ) {
        return new ProductImageResponse(
                image.getId(),
                imageUrl,
                image.getSortOrder()
        );
    }
}
