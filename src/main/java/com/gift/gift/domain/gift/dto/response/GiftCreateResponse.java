package com.gift.gift.domain.gift.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GiftCreateResponse(
        Gift gift
) {

    public static GiftCreateResponse from(Gift gift) {
        return new GiftCreateResponse(gift);
    }

    public record Gift(
            Long giftId,
            LocalDateTime sentAt,
            String recipientName,
            Product product
    ) {
    }

    public record Product(
            String productName,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice,
            String imageUrl
    ) {
    }
}
