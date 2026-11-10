package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;
import com.gift.gift.domain.notification.repository.NotificationCommonOutboxRepository;

@Service
@RequiredArgsConstructor
public class NotificationOutboxWriter {
    private final NotificationOutboxRepository repository;
    private final NotificationCommonOutboxRepository commonRepository;

    @Value("${app.notification.common-outbox.enabled:false}")
    private boolean commonOutboxEnabled;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NotificationCreateCommand command) {
        if (commonOutboxEnabled) {
            commonRepository.insert(command);
        } else {
            repository.insert(command);
        }
    }
}
