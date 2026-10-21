package com.gift.gift.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;

@Service
@RequiredArgsConstructor
public class NotificationOutboxWriter {
    private final NotificationOutboxRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NotificationCreateCommand command) {
        repository.insert(command);
    }
}
