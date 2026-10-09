package com.gift.gift.domain.gift.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GiftSentDetailResponse(
        Long giftId,
        LocalDateTime sentAt,
        Recipient recipient,
        Product product,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
) {

    public static GiftSentDetailResponse from(
            Long giftId,
            LocalDateTime sentAt,
            Recipient recipient,
            Product product,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {
        return new GiftSentDetailResponse(
                giftId,
                sentAt,
                recipient,
                product,
                quantity,
                unitPrice,
                totalPrice
        );
    }

    public record Recipient(
            Long userId,
            String name
    ) {
    }

    public record Product(
            Long productId,
            String name,
            String brand,
            String imageUrl
    ) {
    }
}
