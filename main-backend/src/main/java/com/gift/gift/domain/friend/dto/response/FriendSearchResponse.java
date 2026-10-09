package com.gift.gift.domain.friend.dto.response;

import java.util.List;

import com.gift.gift.global.pagination.CursorPageResponse;

public record FriendSearchResponse(
        List<FriendListItem> friends,
        CursorPageResponse.Pagination pagination
) {

    public FriendSearchResponse {
        friends = List.copyOf(friends);
    }

    public static FriendSearchResponse from(
            CursorPageResponse<FriendListItem> page
    ) {
        return new FriendSearchResponse(
                page.items(),
                page.pagination()
        );
    }
}
