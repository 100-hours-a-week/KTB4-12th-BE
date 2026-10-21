package com.gift.gift.domain.notification.query;

import java.util.List;

import com.gift.gift.domain.notification.repository.NotificationQueryRow;

public record NotificationPage(
        List<NotificationQueryRow> items,
        boolean hasNext,
        String nextCursor
) {

    public NotificationPage {
        items = List.copyOf(items);
    }
}
