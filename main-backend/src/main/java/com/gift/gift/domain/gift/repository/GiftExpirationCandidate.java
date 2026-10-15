package com.gift.gift.domain.gift.repository;

import java.time.LocalDateTime;

public record GiftExpirationCandidate(
        Long giftId,
        Long senderId,
        Long orderRecordId,
        LocalDateTime expiresAt
) {
}
