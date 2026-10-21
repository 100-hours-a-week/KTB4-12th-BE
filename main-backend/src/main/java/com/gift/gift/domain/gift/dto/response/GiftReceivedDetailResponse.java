package com.gift.gift.domain.gift.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.gift.gift.domain.gift.entity.GiftStatus;

public record GiftReceivedDetailResponse(
        Long giftId,
        String orderId,
        GiftStatus giftStatus,
        OffsetDateTime expiresAt,
        LocalDateTime receivedAt,
        Sender sender,
        Product product,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
) {

    public static GiftReceivedDetailResponse from(
            Long giftId,
            String orderId,
            GiftStatus giftStatus,
            OffsetDateTime expiresAt,
            LocalDateTime receivedAt,
            Sender sender,
            Product product,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {
        return new GiftReceivedDetailResponse(
                giftId,
                orderId,
                giftStatus,
                expiresAt,
                receivedAt,
                sender,
                product,
                quantity,
                unitPrice,
                totalPrice
        );
    }

    public record Sender(
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
