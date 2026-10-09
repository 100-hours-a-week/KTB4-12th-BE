package com.gift.gift.domain.gift.dto.response;

import java.math.BigDecimal;

public record GiftPreflightResponse(
        Recipient recipient,
        Product product,
        PreferenceWarning preferenceWarning
) {

    public static GiftPreflightResponse from(
            Recipient recipient,
            Product product,
            PreferenceWarning preferenceWarning
    ) {
        return new GiftPreflightResponse(recipient, product, preferenceWarning);
    }

    public record Recipient(
            Long userId,
            String name
    ) {
    }

    public record Product(
            Long productId,
            BigDecimal unitPrice,
            Integer quantity,
            BigDecimal totalPrice,
            Integer maxOrderQuantity
    ) {
    }

    public record PreferenceWarning(
            Long categoryId,
            String categoryName
    ) {
    }
}
