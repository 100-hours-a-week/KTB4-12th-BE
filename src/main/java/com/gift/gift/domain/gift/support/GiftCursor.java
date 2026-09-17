package com.gift.gift.domain.gift.support;

import java.time.LocalDateTime;

public record GiftCursor(
        LocalDateTime lastCompletedAt,
        Long lastId
) {

    public GiftCursor {
        if (lastCompletedAt == null || lastId == null || lastId <= 0) {
            throw new IllegalArgumentException("커서의 완료 시각과 선물 ID는 필수입니다.");
        }
    }
}
