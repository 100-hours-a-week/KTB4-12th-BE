package com.gift.gift.domain.gift.event;

import java.util.Objects;

public record GiftCreatedEvent(
        Long giftId,
        Long recipientId,
        String productName
) {

    public GiftCreatedEvent {
        Objects.requireNonNull(giftId, "giftId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(productName, "productName must not be null");
    }
}
