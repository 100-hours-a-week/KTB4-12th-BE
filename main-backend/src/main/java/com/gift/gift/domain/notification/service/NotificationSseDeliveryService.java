package com.gift.gift.domain.notification.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository;
import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository.Delivery;

@Service
@RequiredArgsConstructor
public class NotificationSseDeliveryService {
    private final NotificationSseDeliveryRepository repository;

    @Transactional(readOnly = true)
    public List<Delivery> findDue(String nodeId, int batchSize) {
        return repository.findDue(nodeId, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeBeforeSend(Long deliveryId, String nodeId) {
        return repository.completeBeforeSend(deliveryId, nodeId);
    }
}
