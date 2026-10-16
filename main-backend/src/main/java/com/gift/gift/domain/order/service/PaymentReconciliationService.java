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
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Slf4j
@Service
public class PaymentReconciliationService {

    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final OrderApprovalTransactionService approvalService;
    private final PaymentCompletionService completionService;
    private final PaymentLocalCompletionRetry localCompletionRetry;
    private final PaymentReconciliationTransactionService transactionService;
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
            PaymentReconciliationTransactionService transactionService,
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
        this.transactionService = transactionService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.staleAge = staleAge;
        this.timeout = timeout;
    }

    public void reconcilePendingPayments() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Order> candidates = orderRepository.findReconciliationCandidates(
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                PaymentApprovalResult.APPROVED,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED,
                now.minus(staleAge),
                PageRequest.of(0, batchSize)
        );

        for (Order order : candidates) {
            try {
                reconcile(order, now);
            } catch (RuntimeException exception) {
                log.error(
                        "결제 재조정에 실패했습니다. orderId={}",
                        order.getOrderId(),
                        exception
                );
            }
        }
    }

    private void reconcile(Order order, LocalDateTime now) {
        if (order.getApprovalResult() == PaymentApprovalResult.APPROVED) {
            reconcileApproved(order, now);
            return;
        }

        reconcileUnknown(order, now);
    }

    private void reconcileUnknown(Order order, LocalDateTime now) {
        try {
            PaymentResponse response = paymentClient.getByOrderId(order.getOrderId());

            if (response != null && response.status() == PaymentServerStatus.COMPLETED) {
                validateCompletedResponse(order, response);
                approvalService.record(
                        order.getId(),
                        response.paymentKey(),
                        response.transactionKey(),
                        response.approvedAt()
                );
                completeLocal(order);
                return;
            }

            if (response != null && response.status() == PaymentServerStatus.DECLINED) {
                approvalService.decline(order.getId());
                return;
            }

            keepUnknownOrRequireReview(order, now, OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED);
        } catch (PaymentClientException exception) {
            OrderFailureCode failureCode =
                    exception.failure() == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                            ? OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE
                            : OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED;

            keepUnknownOrRequireReview(order, now, failureCode);
        } catch (RuntimeException exception) {
            if (isExpired(order, now)) {
                transactionService.markRequired(order.getId());
                log.error(
                        "결제 조회 결과 검증 실패로 운영 확인 대상으로 전환했습니다. orderId={}",
                        order.getOrderId(),
                        exception
                );
                return;
            }

            throw exception;
        }
    }

    private void keepUnknownOrRequireReview(
            Order order,
            LocalDateTime now,
            OrderFailureCode failureCode
    ) {
        if (isExpired(order, now)) {
            transactionService.markRequired(order.getId());
            return;
        }

        approvalService.markUnknown(order.getId(), failureCode);
    }

    private boolean isExpired(Order order, LocalDateTime now) {
        return !order.getCreatedAt().plus(timeout).isAfter(now);
    }

    private void reconcileApproved(Order order, LocalDateTime now) {
        if (order.getApprovedAt() != null
                && !order.getApprovedAt().plus(timeout).isAfter(now)) {
            log.warn(
                    "로컬 결제 복구 기한이 만료되어 자동 보상 취소를 기다립니다. orderId={}",
                    order.getOrderId()
            );
            return;
        }

        completeLocal(order);
    }

    private void completeLocal(Order order) {
        try {
            localCompletionRetry.execute(
                    order.getOrderId(),
                    () -> completionService.complete(order.getId())
            );
        } catch (RuntimeException exception) {
            approvalService.markLocalFailure(order.getId());
            log.error(
                    "결제 재조정 중 로컬 완료 처리에 실패했습니다. orderId={}, failureType={}",
                    order.getOrderId(),
                    LocalPaymentFailureClassifier.classify(exception),
                    exception
            );
        }
    }

    private void validateCompletedResponse(Order order, PaymentResponse response) {
        boolean valid = Objects.equals(order.getOrderId(), response.orderId())
                && Objects.equals(order.getAmount(), response.amount())
                && response.status() == PaymentServerStatus.COMPLETED
                && hasText(response.paymentKey())
                && hasText(response.transactionKey())
                && response.approvedAt() != null;

        if (!valid) {
            throw new IllegalStateException("Payment Server 조회 결과가 기존 주문과 일치하지 않습니다.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
