package com.gift.gift.domain.friend.dto.response;

import java.time.LocalDateTime;

import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.entity.FriendRequestStatus;

public record FriendRequestResponse(
        Long requestId,
        FriendRequestStatus status,
        Long requesterId,
        String requesterName,
        Long receiverId,
        String receiverName,
        LocalDateTime createdAt
) {
    public static FriendRequestResponse from(FriendRequest request) {
        return new FriendRequestResponse(
                request.getId(), request.getStatus(),
                request.getRequester().getId(), request.getRequester().getName(),
                request.getReceiver().getId(), request.getReceiver().getName(), request.getCreatedAt()
        );
    }
}
