package com.gift.gift.domain.friend.dto.response;

import java.util.Objects;

import com.gift.gift.domain.user.entity.User;

public record FriendCreateResponse(
        Long friendUserId,
        String friendName
) {

    public static FriendCreateResponse from(User friendUser) {
        Objects.requireNonNull(friendUser, "friendUser must not be null");

        return new FriendCreateResponse(
                friendUser.getId(),
                friendUser.getName()
        );
    }
}
