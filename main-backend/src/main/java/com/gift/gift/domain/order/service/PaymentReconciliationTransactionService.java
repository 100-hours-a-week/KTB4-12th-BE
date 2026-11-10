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
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskService;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskType;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
public class PaymentReconciliationTransactionService {

    private final OrderRepository orderRepository;
    private final PaymentRecoveryTaskService recoveryTaskService;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleNext(
            Long orderRecordId,
            OrderFailureCode failureCode,
            LocalDateTime nextAt
    ) {
        return scheduleNext(orderRecordId, failureCode, nextAt, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleNext(
            Long orderRecordId,
            OrderFailureCode failureCode,
            LocalDateTime nextAt,
            String token
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = orderRepository.scheduleUnknownReconciliation(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED,
                failureCode,
                now,
                nextAt
        );

        if (updated == 1 && token != null) {
            recoveryTaskService.scheduleNext(
                    PaymentRecoveryTaskType.APPROVAL,
                    orderRecordId,
                    token,
                    nextAt,
                    failureCode.name()
            );
        }

        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTask(Long orderRecordId, String token) {
        recoveryTaskService.completeSync(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTaskReview(
            Long orderRecordId,
            String token,
            OrderFailureCode failureCode
    ) {
        recoveryTaskService.markSyncReview(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token,
                failureCode.name()
        );
    }
}
