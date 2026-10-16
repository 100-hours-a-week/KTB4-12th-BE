package com.gift.gift.domain.notification.event.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gift.gift.domain.gift.event.GiftCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.service.NotificationOutboxWriter;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;

@Component
@RequiredArgsConstructor
public class GiftNotificationEventHandler {

    private static final String MESSAGE_SUFFIX = " 선물이 도착했어요.";
    private static final int MAX_PRODUCT_NAME_CODE_POINTS = 255 - MESSAGE_SUFFIX.length();

    private final NotificationOutboxWriter outboxWriter;
    private final NotificationDeduplicationKeyGenerator keyGenerator;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handle(GiftCreatedEvent event) {
        NotificationType type = NotificationType.GIFT_RECEIVED;
        String productName = event.productName();
        if (productName.codePointCount(0, productName.length()) > MAX_PRODUCT_NAME_CODE_POINTS) {
            productName = productName.substring(0, productName.offsetByCodePoints(0, MAX_PRODUCT_NAME_CODE_POINTS));
        }
        outboxWriter.enqueue(new NotificationCreateCommand(
                event.recipientId(),
                event.giftId().toString(),
                type,
                NotificationReferenceType.GIFT,
                event.giftId(),
                "선물이 도착했어요",
                productName + MESSAGE_SUFFIX,
                keyGenerator.generate(type, event.giftId().toString(), event.recipientId())
        ));
    }
}
