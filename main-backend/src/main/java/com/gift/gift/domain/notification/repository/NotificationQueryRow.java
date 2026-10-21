package com.gift.gift.domain.notification.repository;

import java.time.LocalDateTime;

import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;

public record NotificationQueryRow(
        Long notificationId,
        NotificationType type,
        String title,
        String message,
        NotificationReferenceType referenceType,
        Long referenceId,
        LocalDateTime notifiedAt,
        LocalDateTime readAt
) {
}
