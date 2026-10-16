package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Service
@RequiredArgsConstructor
public class PaymentAutoCancellationTransactionService {

    private final OrderRepository orderRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(PaymentCancellationApproval approval) {
        PaymentCancellation cancellation = cancellationRepository
                .findById(approval.cancellation().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "자동 보상 취소 원장을 찾을 수 없습니다."
                ));
        Order order = orderRepository
                .findById(cancellation.getOrderRecordId())
                .orElseThrow(() -> new IllegalStateException(
                        "자동 보상 취소 주문을 찾을 수 없습니다."
                ));

        if (cancellation.getStatus() == PaymentCancellationStatus.COMPLETED
                && order.getStatus() == OrderStatus.CANCELED) {
            return;
        }

        validate(cancellation, order, approval);

        int orderCanceled = orderRepository.cancelApproved(
                order.getId(),
                OrderStatus.PENDING,
                OrderStatus.CANCELED,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.LOCAL_PROCESSING_FAILED,
                approval.canceledAt()
        );
        int cancellationCompleted = cancellationRepository.complete(
                cancellation.getId(),
                PaymentCancellationStatus.PENDING,
                PaymentCancellationStatus.COMPLETED,
                approval.transactionKey(),
                approval.cancelAmount(),
                approval.canceledAt()
        );

        if (orderCanceled != 1 || cancellationCompleted != 1) {
            throw new IllegalStateException(
                    "자동 보상 취소 상태를 완료로 변경할 수 없습니다."
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReconciliationRequired(
            Long orderRecordId,
            Long cancellationId
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        int orderUpdated = orderRepository.markAutoCancellationRequired(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.AUTO_CANCELLATION_RECONCILIATION_REQUIRED,
                now
        );
        int cancellationUpdated = cancellationRepository.markFailure(
                cancellationId,
                PaymentCancellationStatus.PENDING,
                PaymentCancellationFailureCode.CANCELLATION_RECONCILIATION_REQUIRED,
                now
        );

        if (orderUpdated != 1 || cancellationUpdated != 1) {
            throw new IllegalStateException(
                    "자동 보상 취소를 운영 확인 대상으로 변경할 수 없습니다."
            );
        }
    }

    private void validate(
            PaymentCancellation cancellation,
            Order order,
            PaymentCancellationApproval approval
    ) {
        boolean valid = cancellation.getCancellationSource()
                == CancellationSource.LOCAL_PROCESSING_FAILURE
                && cancellation.getStatus() == PaymentCancellationStatus.PENDING
                && order.getStatus() == OrderStatus.PENDING
                && order.getApprovalResult() == PaymentApprovalResult.APPROVED
                && order.getAmount().equals(approval.cancelAmount());

        if (!valid) {
            throw new IllegalStateException(
                    "자동 보상 취소 대상의 상태가 올바르지 않습니다."
            );
        }
    }
}
