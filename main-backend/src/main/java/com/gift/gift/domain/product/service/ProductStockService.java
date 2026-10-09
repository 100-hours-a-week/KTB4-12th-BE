package com.gift.gift.domain.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.repository.ProductRepository;

@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;

    @Transactional
    public boolean deductStockIfAvailable(Long productId, int quantity) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 양수여야 합니다.");
        }

        return productRepository.deductStockIfAvailable(productId, quantity) == 1;
    }
}
