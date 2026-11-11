package com.gift.gift.domain.order.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskService;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskType;
import com.gift.gift.domain.order.repository.OrderRepository;

@Service
@RequiredArgsConstructor
public class OrderApprovalTransactionService {

    private static final Duration SYNC_REQUEST_LEASE = Duration.ofSeconds(10);
    private static final Duration FIRST_RECONCILIATION_DELAY = Duration.ofSeconds(5);

    private final OrderRepository orderRepository;
    private final OrderCouponService orderCouponService;
    private final java.time.Clock clock;
    private final PaymentRecoveryTaskService recoveryTaskService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long orderRecordId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return orderRepository.claimApproval(
                orderRecordId,
                now,
                now.plus(SYNC_REQUEST_LEASE)
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long orderRecordId, String token) {
        recoveryTaskService.requireSyncOwner(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token
        );
        return claim(orderRecordId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long orderRecordId,
            String paymentKey,
            String transactionKey,
            LocalDateTime approvedAt
    ) {
        record(orderRecordId, null, paymentKey, transactionKey, approvedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long orderRecordId,
            String token,
            String paymentKey,
            String transactionKey,
            LocalDateTime approvedAt
    ) {
        requireOwner(orderRecordId, token);
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = orderRepository.recordApproval(
                orderRecordId,
                paymentKey,
                transactionKey,
                PaymentApprovalResult.APPROVED,
                PaymentApprovalResult.UNKNOWN,
                approvedAt,
                now,
                OrderStatus.PENDING
        );

        if (updated == 1) {
            return;
        }

        Order order = findOrder(orderRecordId);
        boolean sameApproval =
                order.getApprovalResult() == PaymentApprovalResult.APPROVED
                        && Objects.equals(order.getPaymentKey(), paymentKey)
                        && Objects.equals(
                        order.getApprovedTransactionKey(),
                        transactionKey
                )
                        && Objects.equals(order.getApprovedAt(), approvedAt);

        if (!sameApproval) {
            throw new IllegalStateException(
                    "기존 주문의 승인 결과와 Payment Server 응답이 일치하지 않습니다."
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(Long orderRecordId) {
        decline(orderRecordId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(Long orderRecordId, String token) {
        requireOwner(orderRecordId, token);
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = orderRepository.decline(
                orderRecordId,
                OrderStatus.PENDING,
                OrderStatus.DECLINED,
                PaymentApprovalResult.DECLINED,
                OrderFailureCode.PAYMENT_DECLINED,
                now
        );

        if (updated == 0
                && findOrder(orderRecordId).getStatus() != OrderStatus.DECLINED) {
            throw new IllegalStateException("결제 거절 결과를 주문에 반영할 수 없습니다.");
        }

        orderCouponService.restoreAfterFailure(orderRecordId);
        completeTask(orderRecordId, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(
            Long orderRecordId,
            OrderFailureCode failureCode
    ) {
        markUnknown(orderRecordId, null, failureCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(
            Long orderRecordId,
            String token,
            OrderFailureCode failureCode
    ) {
        requireOwner(orderRecordId, token);
        if (failureCode != OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
                && failureCode != OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "승인 미확정 상태에 사용할 수 없는 실패 코드입니다."
            );
        }

        LocalDateTime now = LocalDateTime.now(clock);
        orderRepository.markUnknown(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                failureCode,
                now,
                now.plus(FIRST_RECONCILIATION_DELAY)
        );
        readyTask(orderRecordId, token, failureCode.name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markLocalFailure(Long orderRecordId) {
        markLocalFailure(orderRecordId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markLocalFailure(Long orderRecordId, String token) {
        requireOwner(orderRecordId, token);
        int updated = orderRepository.markLocalFailure(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.LOCAL_PROCESSING_FAILED,
                LocalDateTime.now(clock)
        );

        if (updated == 1) {
            readyTask(
                    orderRecordId,
                    token,
                    OrderFailureCode.LOCAL_PROCESSING_FAILED.name()
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markLocalFailure(
            Long orderRecordId,
            String token,
            LocalDateTime nextRunAt
    ) {
        requireOwner(orderRecordId, token);
        int updated = orderRepository.markLocalFailure(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.LOCAL_PROCESSING_FAILED,
                LocalDateTime.now(clock)
        );

        if (updated == 1 && token != null) {
            recoveryTaskService.scheduleNext(
                    PaymentRecoveryTaskType.APPROVAL,
                    orderRecordId,
                    token,
                    nextRunAt,
                    OrderFailureCode.LOCAL_PROCESSING_FAILED.name()
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentCompletion completeWithOwner(
            Long orderRecordId,
            String token,
            Supplier<PaymentCompletion> completion
    ) {
        recoveryTaskService.requireSyncOwner(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token
        );
        PaymentCompletion result = completion.get();
        recoveryTaskService.completeSync(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token
        );
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReview(Long orderRecordId, String token) {
        recoveryTaskService.requireSyncOwner(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token
        );
        orderRepository.markReconciliationRequired(
                orderRecordId,
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED,
                LocalDateTime.now(clock)
        );
        recoveryTaskService.markSyncReview(
                PaymentRecoveryTaskType.APPROVAL,
                orderRecordId,
                token,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED.name()
        );
    }

    private void requireOwner(Long orderRecordId, String token) {
        if (token != null) {
            recoveryTaskService.requireSyncOwner(
                    PaymentRecoveryTaskType.APPROVAL,
                    orderRecordId,
                    token
            );
        }
    }

    private void readyTask(Long orderRecordId, String token, String errorCode) {
        if (token != null) {
            recoveryTaskService.markSyncReady(
                    PaymentRecoveryTaskType.APPROVAL,
                    orderRecordId,
                    token,
                    errorCode
            );
        }
    }

    private void completeTask(Long orderRecordId, String token) {
        if (token != null) {
            recoveryTaskService.completeSync(
                    PaymentRecoveryTaskType.APPROVAL,
                    orderRecordId,
                    token
            );
        }
    }

    private Order findOrder(Long orderRecordId) {
        return orderRepository.findById(orderRecordId)
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다."));
    }
}
