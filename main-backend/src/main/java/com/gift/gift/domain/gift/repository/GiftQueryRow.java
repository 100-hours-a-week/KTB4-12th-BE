package com.gift.gift.domain.gift.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GiftQueryRow(
        Long giftId,
        LocalDateTime completedAt,
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
