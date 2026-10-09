package com.gift.gift.domain.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.repository.ProductViewRepository;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class ProductViewService {

    private final ProductRepository productRepository;
    private final ProductViewRepository productViewRepository;

    @Transactional
    public void recordProductView(Long userId, Long productId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }

        productRepository.findActiveByIdForUpdate(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRODUCT_NOT_FOUND));

        int affectedRows = productViewRepository.recordViewAfterCooldown(
                userId,
                productId
        );

        if (affectedRows == 0) {
            return;
        }
        if (affectedRows != 1 && affectedRows != 2) {
            throw new IllegalStateException(
                    "상품 조회 이력 갱신 결과가 올바르지 않습니다."
            );
        }

        if (productRepository.incrementViewsIfActive(productId) != 1) {
            throw new ProductException(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }
}
