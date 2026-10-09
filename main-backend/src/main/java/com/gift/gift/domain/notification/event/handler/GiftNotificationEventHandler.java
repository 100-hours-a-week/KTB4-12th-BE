package com.gift.gift.domain.notification.event.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gift.gift.domain.gift.event.GiftCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.service.NotificationService;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;

@Component
@RequiredArgsConstructor
public class GiftNotificationEventHandler {

    private final NotificationService notificationService;
    private final NotificationDeduplicationKeyGenerator keyGenerator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(GiftCreatedEvent event) {
        NotificationType type = NotificationType.GIFT_RECEIVED;
        notificationService.create(new NotificationCreateCommand(
                event.recipientId(),
                event.giftId().toString(),
                type,
                NotificationReferenceType.GIFT,
                event.giftId(),
                "선물이 도착했어요",
                event.productName() + " 선물이 도착했어요.",
                keyGenerator.generate(type, event.giftId().toString(), event.recipientId())
        ));
    }
}
