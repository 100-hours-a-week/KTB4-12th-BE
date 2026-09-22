package com.gift.gift.domain.friend.query;

import java.util.List;

import com.gift.gift.domain.friend.repository.FriendQueryRow;

public record FriendPage(
        List<FriendQueryRow> items,
        boolean hasNext,
        String nextCursor
) {

    public FriendPage {
        items = List.copyOf(items);
    }
}
