package com.gift.gift.domain.gift.support;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.gift.gift.global.pagination.InvalidCursorException;

public record GiftCursor(
        @JsonProperty("completed_at") LocalDateTime lastCompletedAt,
        @JsonProperty("id") Long lastId
) {

    public GiftCursor {
        if (lastCompletedAt == null || lastId == null || lastId <= 0) {
            throw new InvalidCursorException();
        }
    }
}
