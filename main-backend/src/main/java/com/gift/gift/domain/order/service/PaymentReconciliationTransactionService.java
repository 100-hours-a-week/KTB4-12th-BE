package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
public class PaymentReconciliationTransactionService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRequired(Long orderRecordId) {
        int updated = orderRepository.markReconciliationRequired(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED,
                LocalDateTime.now(clock)
        );

        return updated == 1;
    }
}
