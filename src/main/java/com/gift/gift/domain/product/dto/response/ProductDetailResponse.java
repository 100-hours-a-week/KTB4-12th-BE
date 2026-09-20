package com.gift.gift.domain.product.dto.response;

import java.math.BigDecimal;
import java.util.List;

import com.gift.gift.domain.product.entity.Product;

public record ProductDetailResponse(
        ProductResponse product
) {

    public static ProductDetailResponse from(
            Product product,
            List<ProductImageResponse> images
    ) {
        return new ProductDetailResponse(
                ProductResponse.from(product, images)
        );
    }

    public record ProductResponse(
            Long productId,
            String brandName,
            String productName,
            String description,
            BigDecimal unitPrice,
            List<ProductImageResponse> images,
            Integer stockQuantity
    ) {

        public ProductResponse {
            images = List.copyOf(images);
        }

        public static ProductResponse from(
                Product product,
                List<ProductImageResponse> images
        ) {
            return new ProductResponse(
                    product.getId(),
                    product.getBrand(),
                    product.getName(),
                    product.getDescription(),
                    product.getPrice(),
                    images,
                    product.getQuantity()
            );
        }
    }
}
