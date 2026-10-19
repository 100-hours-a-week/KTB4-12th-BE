package com.gift.gift.domain.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.product.dto.response.ProductDetailResponse;

@Service
@RequiredArgsConstructor
public class ProductDetailService {

    private final ProductQueryService productQueryService;
    private final ProductViewService productViewService;

    public ProductDetailResponse getProductDetail(Long userId, Long productId) {
        ProductDetailResponse response = productQueryService.getProductDetail(productId);
        productViewService.recordProductView(userId, productId);
        return response;
    }
}
