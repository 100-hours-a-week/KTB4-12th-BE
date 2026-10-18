package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.entity.Notification;
import com.gift.gift.domain.notification.repository.NotificationRepository;
import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository;

@Service
@RequiredArgsConstructor
public class NotificationCreateTransactionService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationSseDeliveryRepository sseDeliveryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(NotificationCreateCommand command) {
        persist(command);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveInCurrentTransaction(NotificationCreateCommand command) {
        persist(command);
    }

    private void persist(NotificationCreateCommand command) {
        sseDeliveryRepository.lockRecipient(command.recipientId());
        Notification notification = notificationRepository.save(new Notification(
                command.recipientId(),
                command.type(),
                command.referenceType(),
                command.referenceId(),
                command.title(),
                command.message(),
                command.deduplicationKey()
        ));
        sseDeliveryRepository.enqueue(notification.getId(), command.recipientId());
        eventPublisher.publishEvent(new NotificationCreatedEvent(
                command.recipientId(),
                NotificationListItem.from(notification)
        ));
    }
}
