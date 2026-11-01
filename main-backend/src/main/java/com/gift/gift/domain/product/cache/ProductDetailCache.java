package com.gift.gift.domain.product.cache;

import java.math.BigDecimal;
import java.util.List;

public record ProductDetailCache(
        Long productId,
        String brandName,
        String productName,
        String description,
        BigDecimal unitPrice,
        List<ImageMetadata> images
) {

    public ProductDetailCache {
        images = List.copyOf(images);
    }

    public record ImageMetadata(
            Long imageId,
            String objectKey,
            Integer displayOrder
    ) {
    }
}
