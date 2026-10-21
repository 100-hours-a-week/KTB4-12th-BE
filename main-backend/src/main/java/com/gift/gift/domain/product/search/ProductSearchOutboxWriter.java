package com.gift.gift.domain.product.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductSearchOutboxWriter {

    private final ProductSearchOutboxRepository repository;
    private final boolean enabled;

    public ProductSearchOutboxWriter(
            ProductSearchOutboxRepository repository,
            @Value("${app.product-search.outbox.enabled:false}")
            boolean enabled
    ) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Long productId) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "상품 ID는 양수여야 합니다."
            );
        }

        if (enabled) {
            repository.insert(productId);
        }
    }
}
