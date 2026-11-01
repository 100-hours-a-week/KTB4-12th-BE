package com.gift.gift.domain.product.event;

public record ProductMetadataChangedEvent(Long productId) {

    public ProductMetadataChangedEvent {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }
    }
}
