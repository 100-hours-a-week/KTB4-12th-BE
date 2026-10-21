package com.gift.gift.domain.notification.service;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;

public record NotificationCreatedEvent(
        Long recipientId,
        NotificationListItem notification
) {
}
