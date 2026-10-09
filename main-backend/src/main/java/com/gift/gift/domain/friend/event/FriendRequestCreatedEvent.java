package com.gift.gift.domain.friend.event;

import java.util.Objects;

public record FriendRequestCreatedEvent(Long requestId, Long recipientId, String requesterName) {

    public FriendRequestCreatedEvent {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(requesterName, "requesterName must not be null");
    }
}
