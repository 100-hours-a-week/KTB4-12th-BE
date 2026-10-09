package com.gift.gift.domain.notification.support;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.notification.entity.NotificationType;

@Component
public class NotificationDeduplicationKeyGenerator {

    public String generate(NotificationType type, String sourceIdentifier, Long recipientId) {
        return type + ":" + sourceIdentifier + ":" + recipientId;
    }
}
