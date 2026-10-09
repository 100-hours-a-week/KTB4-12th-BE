package com.gift.gift.domain.notification.dto;

import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;

public record NotificationCreateCommand(
        Long recipientId,
        String sourceIdentifier,
        NotificationType type,
        NotificationReferenceType referenceType,
        Long referenceId,
        String title,
        String message,
        String deduplicationKey
) {
}
