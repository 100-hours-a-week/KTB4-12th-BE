package com.gift.gift.domain.gift.event;

import java.util.Objects;

public record GiftCompletedRewardEvent(
        Long giftId,
        Long senderId,
        String productName,
        long rewardAmount,
        int rewardMultiplier
) {

    public GiftCompletedRewardEvent {
        Objects.requireNonNull(
                giftId,
                "선물 ID는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                senderId,
                "발신인 ID는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                productName,
                "상품명은 null일 수 없습니다."
        );

        if (rewardAmount <= 0) {
            throw new IllegalArgumentException(
                    "보상 금액은 양수여야 합니다."
            );
        }

        if (rewardMultiplier < 1 || rewardMultiplier > 3) {
            throw new IllegalArgumentException(
                    "보상 배수는 1, 2, 3만 허용합니다."
            );
        }
    }

    public GiftCompletedRewardEvent(
            Long giftId,
            Long senderId,
            String productName,
            long rewardAmount
    ) {
        this(
                giftId,
                senderId,
                productName,
                rewardAmount,
                1
        );
    }
}
