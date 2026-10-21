package com.gift.gift.domain.product.dto.response;

import com.gift.gift.domain.product.entity.ProductImage;
import com.gift.gift.domain.product.repository.ProductDetailProjection;

public record ProductImageResponse(
        Long imageId,
        String imageUrl,
        Integer displayOrder
) {

    public static ProductImageResponse from(ProductDetailProjection row, String imageUrl) {
        return new ProductImageResponse(row.imageId(), imageUrl, row.sortOrder());
    }

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
