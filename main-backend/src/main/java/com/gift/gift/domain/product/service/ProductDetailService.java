package com.gift.gift.domain.product.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.gift.gift.domain.product.cache.ProductDetailCacheService;
import com.gift.gift.domain.product.cache.ProductDetailCacheMetrics;
import com.gift.gift.domain.product.dto.response.ProductDetailResponse;

@Service
@RequiredArgsConstructor
public class ProductDetailService {

    private final ProductDetailCacheService productDetailCacheService;
    private final ProductViewService productViewService;
    private final ProductDetailCacheMetrics metrics;

    public ProductDetailResponse getProductDetail(
            Long userId,
            Long productId
    ) {
        ProductDetailResponse response =
                productDetailCacheService.getProductDetail(productId);

        metrics.time(ProductDetailCacheMetrics.Stage.VIEW_HISTORY, () -> {
            productViewService.recordProductView(userId, productId);
            return null;
        });

        return response;
    }
}
