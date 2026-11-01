package com.gift.gift.domain.friend.event;

public record FriendshipChangedEvent(Long userId, Long friendUserId) {
}
