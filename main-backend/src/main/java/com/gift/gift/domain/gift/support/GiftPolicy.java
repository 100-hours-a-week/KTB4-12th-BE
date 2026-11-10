package com.gift.gift.domain.gift.support;

import java.time.LocalDateTime;

public final class GiftPolicy {

    public static final int MAX_QUANTITY = 10;
    public static final int ACCEPTANCE_DAYS = 7;
    public static final long BASE_REWARD_AMOUNT = 50_000L;

    private GiftPolicy() {
    }

    public static LocalDateTime expiresAt(LocalDateTime sentAt) {
        if (sentAt == null) {
            throw new IllegalArgumentException("선물 발송 시각은 필수입니다.");
        }

        return sentAt.toLocalDate()
                .plusDays(ACCEPTANCE_DAYS + 1L)
                .atStartOfDay();
    }
}
