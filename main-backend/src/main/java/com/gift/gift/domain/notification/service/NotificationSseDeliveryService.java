package com.gift.gift.domain.notification.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository;
import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository.Delivery;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class NotificationSseDeliveryService {
    private final NotificationSseDeliveryRepository repository;

    @Transactional(readOnly = true)
    public List<Delivery> findDue(String nodeId, int batchSize) {
        return repository.findDue(nodeId, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Long deliveryId, String nodeId) {
        return repository.reserve(deliveryId, nodeId);
    }

    @Transactional
    public void acknowledge(Long recipientId, Long deliveryId) {
        if (!repository.belongsTo(deliveryId, recipientId)) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        repository.acknowledge(deliveryId);
    }
}
