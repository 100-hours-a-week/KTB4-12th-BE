package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.*;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
public class PaymentCancellationTransactionService {

    private final PaymentCancellationRepository cancellationRepository;

    private final Clock clock;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markLocalFailure(Long cancellationId) {
        cancellationRepository.markFailure(
                cancellationId,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationFailureCode.LOCAL_COMPENSATION_FAILED,
                LocalDateTime.now(clock)
        );
    }
}
