package com.gift.gift.domain.notification.support;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.gift.gift.global.pagination.InvalidCursorException;

public record NotificationCursor(
        @JsonProperty("created_at") LocalDateTime lastCreatedAt,
        @JsonProperty("id") Long lastId
) {

    public NotificationCursor {
        if (lastCreatedAt == null || lastId == null || lastId <= 0) {
            throw new InvalidCursorException();
        }
    }
}
