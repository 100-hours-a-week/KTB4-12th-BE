package com.gift.gift.domain.gift.query;

import java.util.List;

import com.gift.gift.domain.gift.repository.GiftQueryRow;

public record GiftPage(
        List<GiftQueryRow> items,
        boolean hasNext,
        String nextCursor
) {

    public GiftPage {
        items = List.copyOf(items);
    }
}
