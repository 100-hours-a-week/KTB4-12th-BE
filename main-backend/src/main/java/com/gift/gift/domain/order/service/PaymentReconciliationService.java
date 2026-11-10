package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskType;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Slf4j
@Service
public class PaymentReconciliationService {

    private static final Duration UNCLAIMED_ORDER_LEASE = Duration.ofSeconds(10);

    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final OrderApprovalTransactionService approvalService;
    private final PaymentCompletionService completionService;
    private final PaymentLocalCompletionRetry localCompletionRetry;
    private final PaymentAutoCancellationService autoCancellationService;
    private final PaymentReconciliationTransactionService transactionService;
    private final PaymentCancellationReconciliationService cancellationReconciliationService;
    private final Clock clock;
    private final int batchSize;
    private final Duration staleAge;
    private final Duration timeout;

    public PaymentReconciliationService(
            OrderRepository orderRepository,
            PaymentClient paymentClient,
            OrderApprovalTransactionService approvalService,
            PaymentCompletionService completionService,
            PaymentLocalCompletionRetry localCompletionRetry,
            PaymentAutoCancellationService autoCancellationService,
            PaymentReconciliationTransactionService transactionService,
            PaymentCancellationReconciliationService cancellationReconciliationService,
            Clock clock,
            @Value("${app.payment.reconciliation.batch-size:100}") int batchSize,
            @Value("${app.payment.reconciliation.stale-age:PT1M}") Duration staleAge,
            @Value("${app.payment.reconciliation.timeout:PT10M}") Duration timeout
    ) {
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
        this.approvalService = approvalService;
        this.completionService = completionService;
        this.localCompletionRetry = localCompletionRetry;
        this.autoCancellationService = autoCancellationService;
        this.transactionService = transactionService;
        this.cancellationReconciliationService = cancellationReconciliationService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.staleAge = staleAge;
        this.timeout = timeout;
    }

    public void reconcilePendingPayments() {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Order> unknownOrders = orderRepository.findDueUnknownPayments(
                now,
                now.minus(UNCLAIMED_ORDER_LEASE),
                PageRequest.of(0, batchSize)
        );

        for (Order order : unknownOrders) {
            try {
                reconcileUnknown(order, now, null);
            } catch (RuntimeException exception) {
                log.error(
                        "결제 승인 결과 재조정에 실패했습니다. orderId={}",
                        order.getOrderId(),
                        exception
                );
            }
        }

        List<Order> approvedOrders = orderRepository.findStaleApprovedPayments(
                OrderStatus.PENDING,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED,
                OrderFailureCode.AUTO_CANCELLATION_RECONCILIATION_REQUIRED,
                now.minus(staleAge),
                PageRequest.of(0, batchSize)
        );

        for (Order order : approvedOrders) {
            try {
                reconcileApproved(order, now, null);
            } catch (RuntimeException exception) {
                log.error(
                        "승인 후 로컬 처리를 재개하지 못했습니다. orderId={}",
                        order.getOrderId(),
                        exception
                );
            }
        }

        cancellationReconciliationService.reconcilePendingCancellations();
    }

    public void reconcileTask(Long orderRecordId, String token) {
        Order order = orderRepository.findById(orderRecordId)
                .orElseThrow(() -> new IllegalStateException("복구 대상 주문을 찾을 수 없습니다."));

        if (order.getStatus() != OrderStatus.PENDING) {
            transactionService.completeTask(orderRecordId, token);
            return;
        }

        if (order.getFailureCode()
                == OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED
                || order.getFailureCode()
                == OrderFailureCode.AUTO_CANCELLATION_RECONCILIATION_REQUIRED) {
            transactionService.markTaskReview(
                    orderRecordId,
                    token,
                    order.getFailureCode()
            );
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (order.getApprovalResult() == PaymentApprovalResult.APPROVED) {
            reconcileApproved(order, now, token);
        } else {
            reconcileUnknown(order, now, token);
        }
    }

    private void reconcileUnknown(
            Order order,
            LocalDateTime now,
            String token
    ) {
        boolean expired = isExpired(order, now);

        try {
            PaymentResponse response = paymentClient.getByOrderId(order.getOrderId());

            if (response == null) {
                scheduleOrRequireReview(
                        order,
                        now,
                        expired,
                        OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED,
                        token
                );
                return;
            }

            if (!matchesOrderIdentity(order, response)) {
                markRequired(order, token);
                log.error(
                        "결제 상태 조회 응답의 주문 정보가 일치하지 않아 운영 확인 대상으로 전환했습니다. "
                                + "orderId={}",
                        order.getOrderId()
                );
                return;
            }

            if (response.status() == PaymentServerStatus.COMPLETED) {
                if (!isValidCompletedResponse(response)) {
                    markRequired(order, token);
                    log.error(
                            "승인 완료 응답의 필수 값이 없어 운영 확인 대상으로 전환했습니다. "
                                    + "orderId={}",
                            order.getOrderId()
                    );
                    return;
                }

                approvalService.record(
                        order.getId(),
                        token,
                        response.paymentKey(),
                        response.transactionKey(),
                        response.approvedAt()
                );
                completeLocal(order, token);
                return;
            }

            if (response.status() == PaymentServerStatus.DECLINED) {
                approvalService.decline(order.getId(), token);
                return;
            }

            scheduleOrRequireReview(
                    order,
                    now,
                    expired,
                    OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED,
                    token
            );
        } catch (PaymentClientException exception) {
            OrderFailureCode failureCode =
                    exception.failure() == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                            ? OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE
                            : OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED;

            scheduleOrRequireReview(
                    order,
                    now,
                    expired,
                    failureCode,
                    token
            );
        } catch (RuntimeException exception) {
            if (expired) {
                markRequired(order, token);
                log.error(
                        "만료 시점의 승인 상태 조회 또는 검증을 완료하지 못해 운영 확인 대상으로 전환했습니다. "
                                + "orderId={}",
                        order.getOrderId(),
                        exception
                );
                return;
            }

            scheduleNext(
                    order,
                    now,
                    OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED,
                    token
            );
            throw exception;
        }
    }

    private void scheduleOrRequireReview(
            Order order,
            LocalDateTime now,
            boolean expired,
            OrderFailureCode failureCode,
            String token
    ) {
        if (expired) {
            markRequired(order, token);
            return;
        }

        scheduleNext(order, now, failureCode, token);
    }

    private void scheduleNext(
            Order order,
            LocalDateTime now,
            OrderFailureCode failureCode,
            String token
    ) {
        transactionService.scheduleNext(
                order.getId(),
                failureCode,
                now.plus(nextDelay(order.getReconciliationAttempts())),
                token
        );
    }

    private Duration nextDelay(int completedReconciliationAttempts) {
        return switch (Math.min(completedReconciliationAttempts, 3)) {
            case 0 -> Duration.ofSeconds(15);
            case 1 -> Duration.ofSeconds(30);
            default -> Duration.ofSeconds(60);
        };
    }

    private boolean isExpired(Order order, LocalDateTime now) {
        LocalDateTime startedAt = order.getReconciliationStartedAt() != null
                ? order.getReconciliationStartedAt()
                : order.getCreatedAt();

        return startedAt != null
                && !startedAt.plus(timeout).isAfter(now);
    }

    private void reconcileApproved(
            Order order,
            LocalDateTime now,
            String token
    ) {
        if (order.getFailureCode()
                == OrderFailureCode.AUTO_CANCELLATION_PROCESSING) {
            autoCancellationService.cancel(order);
            completeTaskIfOwned(order, token);
            return;
        }

        if (order.getApprovedAt() != null
                && !order.getApprovedAt().plus(timeout).isAfter(now)) {
            log.warn(
                    "로컬 결제 복구 기한이 만료되어 자동 보상 취소를 시작합니다. orderId={}",
                    order.getOrderId()
            );
            autoCancellationService.cancel(order);
            completeTaskIfOwned(order, token);
            return;
        }

        completeLocal(order, token);
    }

    private void completeLocal(Order order, String token) {
        try {
            if (token == null) {
                localCompletionRetry.execute(
                        order.getOrderId(),
                        () -> completionService.complete(order.getId())
                );
            } else {
                approvalService.completeWithOwner(
                        order.getId(),
                        token,
                        () -> localCompletionRetry.execute(
                                order.getOrderId(),
                                () -> completionService.complete(order.getId())
                        )
                );
            }
        } catch (RuntimeException exception) {
            LocalPaymentFailureType failureType =
                    LocalPaymentFailureClassifier.classify(exception);

            if (token == null) {
                approvalService.markLocalFailure(order.getId());
            } else {
                approvalService.markLocalFailure(
                        order.getId(),
                        token,
                        LocalDateTime.now(clock).plus(staleAge)
                );
            }

            log.error(
                    "결제 재조정 중 로컬 완료 처리에 실패했습니다. orderId={}, failureType={}",
                    order.getOrderId(),
                    failureType,
                    exception
            );

            if (requiresAutoCancellation(failureType)) {
                autoCancellationService.cancel(order);
            }
        }
    }

    private void markRequired(Order order, String token) {
        if (token == null) {
            transactionService.markRequired(order.getId());
        } else {
            approvalService.markReview(order.getId(), token);
        }
    }

    private void completeTaskIfOwned(Order order, String token) {
        if (token != null) {
            transactionService.completeTask(order.getId(), token);
        }
    }

    private boolean requiresAutoCancellation(
            LocalPaymentFailureType failureType
    ) {
        return failureType == LocalPaymentFailureType.PERMANENT_BUSINESS
                || failureType
                == LocalPaymentFailureType.DATA_INTEGRITY_OR_PROGRAMMING
                || failureType == LocalPaymentFailureType.UNKNOWN;
    }

    private boolean matchesOrderIdentity(
            Order order,
            PaymentResponse response
    ) {
        return Objects.equals(order.getOrderId(), response.orderId())
                && Objects.equals(order.getAmount(), response.amount());
    }

    private boolean isValidCompletedResponse(PaymentResponse response) {
        return response.status() == PaymentServerStatus.COMPLETED
                && hasText(response.paymentKey())
                && hasText(response.transactionKey())
                && response.approvedAt() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
