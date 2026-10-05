package com.gift.gift.domain.notification.dto.response;

import java.time.LocalDateTime;

import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationQueryRow;

public record NotificationListItem(
        Long notificationId,
        NotificationType type,
        String title,
        String message,
        NotificationReferenceType referenceType,
        Long referenceId,
        LocalDateTime notifiedAt,
        boolean isRead
) {

    public static NotificationListItem from(NotificationQueryRow row) {
        return new NotificationListItem(
                row.notificationId(),
                row.type(),
                row.title(),
                row.message(),
                row.referenceType(),
                row.referenceId(),
                row.notifiedAt(),
                row.readAt() != null
        );
    }
}
