package com.gift.gift.domain.friend.support;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.gift.gift.global.pagination.InvalidCursorException;

public record FriendCursor(
        String name,
        String email,
        @JsonProperty("friend_id") Long friendId,
        String query
) {

    public FriendCursor {
        if (name == null || email == null || friendId == null || friendId <= 0) {
            throw new InvalidCursorException();
        }
    }
}
