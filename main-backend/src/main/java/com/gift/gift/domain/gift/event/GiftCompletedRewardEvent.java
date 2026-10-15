package com.gift.gift.domain.gift.event;

import java.util.Objects;

public record GiftCompletedRewardEvent(Long giftId, Long senderId, String productName) {

    public GiftCompletedRewardEvent {

        Objects.requireNonNull(giftId, "선물 ID는 null일 수 없습니다.");

        Objects.requireNonNull(senderId, "발신인 ID는 null일 수 없습니다.");

        Objects.requireNonNull(productName, "상품명은 null일 수 없습니다.");
    }
}
