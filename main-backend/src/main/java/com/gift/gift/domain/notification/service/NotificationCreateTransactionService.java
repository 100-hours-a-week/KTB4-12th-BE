package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.Notification;
import com.gift.gift.domain.notification.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationCreateTransactionService {

    private final NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(NotificationCreateCommand command) {
        notificationRepository.save(new Notification(
                command.recipientId(),
                command.type(),
                command.referenceType(),
                command.referenceId(),
                command.title(),
                command.message(),
                command.deduplicationKey()
        ));
    }
}
