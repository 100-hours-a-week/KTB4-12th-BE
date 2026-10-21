package com.gift.gift.domain.order.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order create(
            Long payerId,
            String idempotencyKey,
            String requestHash,
            PaymentCreateRequest request,
            PaymentValidation validation
    ) {
        Order order = new Order(
                UUID.randomUUID().toString(),
                payerId,
                request.recipientUserId(),
                request.productId(),
                request.quantity(),
                validation.unitPrice(),
                validation.amount(),
                idempotencyKey,
                requestHash
        );

        return orderRepository.saveAndFlush(order);
    }
}
