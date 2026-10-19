package com.gift.gift.domain.notification.service;

import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository;

@Service
@RequiredArgsConstructor
public class NotificationSseConnectionService {
    private final NotificationSseDeliveryRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void register(String connectionId, String nodeId, Long recipientId) {
        repository.lockRecipient(recipientId);
        repository.register(connectionId, nodeId, recipientId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disconnect(String connectionId) {
        repository.deleteConnection(connectionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<String> renew(String nodeId) {
        repository.renew(nodeId);
        repository.deleteExpiredConnections();
        return repository.activeConnections(nodeId);
    }
}
