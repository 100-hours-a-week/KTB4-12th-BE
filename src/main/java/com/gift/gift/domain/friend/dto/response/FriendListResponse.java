package com.gift.gift.domain.friend.dto.response;

import java.util.List;

import com.gift.gift.global.pagination.CursorPageResponse;

public record FriendListResponse(
        List<FriendListItem> friends,
        CursorPageResponse.Pagination pagination
) {

    public FriendListResponse {
        friends = List.copyOf(friends);
    }

    public static FriendListResponse from(
            CursorPageResponse<FriendListItem> page
    ) {
        return new FriendListResponse(
                page.items(),
                page.pagination()
        );
    }
}
