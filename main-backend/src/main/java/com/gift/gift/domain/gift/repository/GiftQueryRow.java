package com.gift.gift.domain.gift.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.gift.gift.domain.gift.entity.GiftStatus;

public record GiftQueryRow(
        Long giftId,
        Long orderRecordId,
        LocalDateTime createdAt,
        GiftStatus giftStatus,
        LocalDateTime expiresAt,
        Long counterpartUserId,
        String counterpartName,
        LocalDateTime counterpartDeletedAt,
        Long productId,
        String productNameSnapshot,
        BigDecimal productPriceSnapshot,
        Integer quantity,
        String productBrand
) {
}
