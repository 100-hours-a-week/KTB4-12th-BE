package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.*;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
public class PaymentCancellationTransactionService {

    private final PaymentCancellationRepository cancellationRepository;
    private final OrderRepository orderRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final Clock clock;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public PaymentCancellation create(
            Order order,
            Long requesterId,
            CancellationSource source,
            String idempotencyKey,
            String requestHash
    ) {
        if (source == CancellationSource.LOCAL_PROCESSING_FAILURE) {
            claimAutomaticCancellation(order);
        } else {
            claimGiftCancellation(order);
        }

        if (cancellationRepository.existsByOrderRecordIdAndStatus(
                order.getId(),
                PaymentCancellationStatus.PENDING
        )) {
            throw new OrderException(OrderErrorCode.CANCELLATION_IN_PROGRESS);
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimUserRequest(Long cancellationId) {
        return cancellationRepository.claimUserRequest(
                cancellationId,
                LocalDateTime.now(clock)
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRemoteCancellation(
            Long cancellationId,
            String transactionKey,
            long cancelAmount,
            LocalDateTime canceledAt
    ) {
        int updated = cancellationRepository.storeRemoteResult(
                cancellationId,
                PaymentCancellationStatus.PENDING,
                transactionKey,
                cancelAmount,
                canceledAt
        );

        if (updated == 1) {
            return;
        }

        PaymentCancellation current = cancellationRepository
                .findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException(
                        "결제 취소 원장을 찾을 수 없습니다."
                ));

        boolean sameResult =
                Objects.equals(current.getCancelTransactionKey(), transactionKey)
                        && Objects.equals(current.getCancelAmount(), cancelAmount)
                        && Objects.equals(current.getCanceledAt(), canceledAt);

        if (!sameResult) {
            throw new IllegalStateException(
                    "저장된 원격 취소 결과와 새 응답이 일치하지 않습니다."
            );
        }
    }

    private void claimGiftCancellation(Order requestedOrder) {
        GiftHistory gift = giftHistoryRepository
                .findForCancellation(requestedOrder.getId())
                .orElseThrow(() -> new OrderException(
                        OrderErrorCode.PAYMENT_NOT_CANCELABLE
                ));

        Order order = orderRepository
                .findByIdForUpdate(requestedOrder.getId())
                .orElseThrow(() -> new OrderException(
                        OrderErrorCode.PAYMENT_NOT_CANCELABLE
                ));

        if (order.getStatus() != OrderStatus.COMPLETED
                || gift.getStatus() != GiftStatus.WAITING_FOR_ACCEPTANCE
                || gift.getDeletedAt() != null) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeclined(Long cancellationId) {
        cancellationRepository.decline(cancellationId, LocalDateTime.now(clock));
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

        Order current = orderRepository
                .findById(order.getId())
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
    public void markReconciliationRequired(Long cancellationId) {
        cancellationRepository.markFailure(
                cancellationId,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationFailureCode.CANCELLATION_RECONCILIATION_REQUIRED,
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
