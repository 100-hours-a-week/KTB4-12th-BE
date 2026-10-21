package com.gift.gift.domain.product.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.search.ProductSearchOutboxWriter;

@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;
    private final ProductSearchOutboxWriter outboxWriter;

    @Transactional
    public boolean deductStockIfAvailable(
            Long productId,
            int quantity
    ) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "상품 ID는 양수여야 합니다."
            );
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "차감 수량은 양수여야 합니다."
            );
        }

        boolean changed =
                productRepository.deductStockIfAvailable(
                        productId,
                        quantity
                ) == 1;

        if (changed) {
            outboxWriter.enqueue(productId);
        }

        return changed;
    }

    @Transactional
    public boolean restoreStock(
            Long productId,
            int quantity
    ) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "상품 ID는 양수여야 합니다."
            );
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "복구 수량은 양수여야 합니다."
            );
        }

        boolean changed =
                productRepository.restoreStock(
                        productId,
                        quantity
                ) == 1;

        if (changed) {
            outboxWriter.enqueue(productId);
        }

        return changed;
    }
}
