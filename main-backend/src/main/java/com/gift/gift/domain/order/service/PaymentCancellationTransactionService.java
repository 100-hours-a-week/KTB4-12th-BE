package com.gift.gift.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
public class PaymentCancellationTransactionService {

    private final PaymentCancellationRepository cancellationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentCancellation create(
            Order order,
            Long requesterId,
            CancellationSource source,
            String idempotencyKey,
            String requestHash
    ) {
        PaymentCancellation cancellation = new PaymentCancellation(
                order.getId(),
                requesterId,
                source,
                idempotencyKey,
                requestHash
        );

        return cancellationRepository.saveAndFlush(cancellation);
    }
}
