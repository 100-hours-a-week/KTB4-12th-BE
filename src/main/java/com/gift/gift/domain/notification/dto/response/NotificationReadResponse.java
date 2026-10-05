package com.gift.gift.domain.notification.dto.response;

public record NotificationReadResponse(
        Long notificationId,
        boolean isRead,
        long unreadCount
) {
}
