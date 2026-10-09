package com.gift.gift.domain.notification.event.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gift.gift.domain.friend.event.FriendRequestCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.service.NotificationService;
import com.gift.gift.domain.notification.support.NotificationDeduplicationKeyGenerator;

@Component
@RequiredArgsConstructor
public class FriendNotificationEventHandler {

    private final NotificationService notificationService;
    private final NotificationDeduplicationKeyGenerator keyGenerator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(FriendRequestCreatedEvent event) {
        NotificationType type = NotificationType.FRIEND_REQUEST_RECEIVED;
        notificationService.create(new NotificationCreateCommand(
                event.recipientId(),
                event.requestId().toString(),
                type,
                NotificationReferenceType.FRIEND_REQUEST,
                event.requestId(),
                "친구 요청이 도착했어요",
                event.requesterName() + "님이 친구 요청을 보냈어요.",
                keyGenerator.generate(type, event.requestId().toString(), event.recipientId())
        ));
    }
}
