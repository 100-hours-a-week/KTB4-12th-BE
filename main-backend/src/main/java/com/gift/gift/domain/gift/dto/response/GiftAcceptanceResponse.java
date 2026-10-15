package com.gift.gift.domain.gift.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.service.GiftAcceptanceResult;

public record GiftAcceptanceResponse(
        Long giftId,
        GiftStatus giftStatus,
        long rewardAmount,
        OffsetDateTime completedAt
) {

    public static GiftAcceptanceResponse from(GiftAcceptanceResult result, ZoneId zoneId) {

        return new GiftAcceptanceResponse(
                result.giftId(),
                result.giftStatus(),
                result.rewardAmount(),
                result.completedAt()
                        .atZone(zoneId)
                        .toOffsetDateTime()
        );
    }
}
