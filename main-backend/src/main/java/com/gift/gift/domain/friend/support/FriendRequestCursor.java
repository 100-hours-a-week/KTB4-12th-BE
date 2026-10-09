package com.gift.gift.domain.friend.support;

import java.time.LocalDateTime;

import com.gift.gift.global.pagination.InvalidCursorException;

public record FriendRequestCursor(LocalDateTime createdAt, Long requestId, Long userId, Boolean received) {
    public FriendRequestCursor {
        if (createdAt == null || requestId == null || requestId <= 0
                || userId == null || userId <= 0 || received == null) {
            throw new InvalidCursorException();
        }
    }
}
