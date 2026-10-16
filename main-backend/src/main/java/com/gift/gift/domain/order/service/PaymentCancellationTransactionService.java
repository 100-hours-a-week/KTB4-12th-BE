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
    public void markResultUnconfirmed(
            Long cancellationId,
            PaymentCancellationFailureCode failureCode
    ) {
        if (failureCode
                != PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
                && failureCode
                != PaymentCancellationFailureCode.PAYMENT_SERVER_UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "취소 결과 미확정 상태에 사용할 수 없는 실패 코드입니다."
            );
        }

        cancellationRepository.markFailure(
                cancellationId,
                PaymentCancellationStatus.PENDING,
                failureCode,
                LocalDateTime.now(clock)
        );
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
