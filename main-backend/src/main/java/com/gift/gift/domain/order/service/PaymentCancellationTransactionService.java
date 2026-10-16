package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.*;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
public class PaymentCancellationTransactionService {

    private final PaymentCancellationRepository cancellationRepository;
    private final OrderRepository orderRepository;

    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentCancellation create(
            Order order,
            Long requesterId,
            CancellationSource source,
            String idempotencyKey,
            String requestHash
    ) {
        if (source == CancellationSource.LOCAL_PROCESSING_FAILURE) {
            claimAutomaticCancellation(order);
        }

        PaymentCancellation cancellation = new PaymentCancellation(
                order.getId(),
                requesterId,
                source,
                idempotencyKey,
                requestHash
        );

        return cancellationRepository.saveAndFlush(cancellation);
    }

    private void claimAutomaticCancellation(Order order) {
        int claimed = orderRepository.markAutoCancellationProcessing(
                order.getId(),
                OrderStatus.PENDING,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.AUTO_CANCELLATION_PROCESSING,
                LocalDateTime.now(clock)
        );

        if (claimed == 1) {
            return;
        }

        Order current = orderRepository.findById(order.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "자동 보상 취소 주문을 찾을 수 없습니다."
                ));

        if (current.getStatus() != OrderStatus.PENDING
                || current.getApprovalResult() != PaymentApprovalResult.APPROVED
                || current.getFailureCode()
                != OrderFailureCode.AUTO_CANCELLATION_PROCESSING) {
            throw new IllegalStateException(
                    "자동 보상 취소를 시작할 수 없는 주문 상태입니다."
            );
        }
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
