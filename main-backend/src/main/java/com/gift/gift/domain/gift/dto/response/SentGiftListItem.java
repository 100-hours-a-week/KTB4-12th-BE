package com.gift.gift.domain.gift.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.gift.gift.domain.gift.entity.GiftStatus;

public record SentGiftListItem(
        Long giftId,
        String orderId,
        GiftStatus giftStatus,
        OffsetDateTime expiresAt,
        LocalDateTime sentAt,
        Recipient recipient,
        Product product,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
) {

    public static SentGiftListItem from(
            Long giftId,
            String orderId,
            GiftStatus giftStatus,
            OffsetDateTime expiresAt,
            LocalDateTime sentAt,
            Recipient recipient,
            Product product,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {
        return new SentGiftListItem(
                giftId,
                orderId,
                giftStatus,
                expiresAt,
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
            String thumbnailUrl
    ) {
    }
}
