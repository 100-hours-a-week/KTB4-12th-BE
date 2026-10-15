package com.gift.gift.domain.gift.service;

import java.time.LocalDateTime;

import com.gift.gift.domain.gift.entity.GiftStatus;

public record GiftAcceptanceResult(
        Long giftId,
        GiftStatus giftStatus,
        long rewardAmount,
        LocalDateTime completedAt
) {
}
