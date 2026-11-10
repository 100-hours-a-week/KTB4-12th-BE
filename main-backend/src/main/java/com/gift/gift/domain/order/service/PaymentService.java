package com.gift.gift.domain.order.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.recovery.StalePaymentRecoveryOwnerException;
import com.gift.gift.infrastructure.payment.PaymentSyncRetryPolicy;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRequestService paymentRequestService;
    private final PaymentClient paymentClient;
    private final PaymentSyncRetryPolicy retryPolicy;
    private final OrderApprovalTransactionService approvalService;
    private final PaymentCompletionService completionService;
    private final PaymentLocalCompletionRetry localCompletionRetry;
    private final PaymentAutoCancellationService autoCancellationService;
    private final PaymentReconciliationTransactionService reconciliationService;

    public PaymentResult pay(
            Long payerId,
            String idempotencyKey,
            String scenario,
            PaymentCreateRequest request
    ) {
        long deadlineNanos = retryPolicy.deadlineFromNow();
        PaymentStartResult start = paymentRequestService.start(payerId, idempotencyKey, request);
        Order order = start.order();

        if (order.getStatus() == OrderStatus.COMPLETED) {
            return PaymentResult.completed(completionService.findCompleted(order.getId()), false);
        }
        if (order.getStatus() == OrderStatus.DECLINED) {
            throw new OrderException(OrderErrorCode.PAYMENT_DECLINED);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT);
        }

        if (start.recoveryTaskManaged() && start.syncOwnerToken() == null) {
            return processing(order, start.created());
        }

        try {
            return payOwned(order, start, scenario, deadlineNanos);
        } catch (StalePaymentRecoveryOwnerException exception) {
            return processing(order, start.created());
        }
    }

    private PaymentResult payOwned(
            Order order,
            PaymentStartResult start,
            String scenario,
            long deadlineNanos
    ) {
        String token = start.syncOwnerToken();

        if (order.getApprovalResult() == PaymentApprovalResult.APPROVED) {
            if (isAutoCancellation(order.getFailureCode())) {
                return processing(order, start.created());
            }
            return complete(order, start, token);
        }

        if (order.getApprovalResult() != null && order.getApprovalResult() != PaymentApprovalResult.UNKNOWN) {
            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT);
        }

        PaymentResponse response = requestApproval(
                order, scenario, deadlineNanos, token
        );
        if (response == null || response.status() == PaymentServerStatus.PENDING) {
            return unresolved(order, start, token);
        }
        if (response.status() == PaymentServerStatus.DECLINED) {
            decline(order.getId(), token);
            throw new OrderException(OrderErrorCode.PAYMENT_DECLINED);
        }
        if (response.status() != PaymentServerStatus.COMPLETED || !isValidCompletedResponse(order, response)) {
            return requireOperationsReview(order, start, token);
        }

        record(
                order.getId(), token, response.paymentKey(),
                response.transactionKey(), response.approvedAt()
        );
        return complete(order, start, token);
    }

    private PaymentResponse requestApproval(
            Order order,
            String scenario,
            long deadlineNanos,
            String token
    ) {
        boolean claimed = token == null
                ? approvalService.claim(order.getId())
                : approvalService.claim(order.getId(), token);

        if (!claimed) {
            return recoverApproval(order, scenario, deadlineNanos, false);
        }
        if (!retryPolicy.canStartCommand(deadlineNanos)) {
            return null;
        }

        try {
            PaymentResponse response = paymentClient.confirm(confirmRequest(order), scenario);
            if (isFinalApprovalResponse(response)) {
                return response;
            }
            return recoverApproval(order, scenario, deadlineNanos, true);
        } catch (PaymentClientException exception) {
            if (!isAmbiguousCommandFailure(exception)) {
                throw convertClientFailure(order, exception, token);
            }
            return recoverApproval(order, scenario, deadlineNanos, true);
        }
    }

    private PaymentResponse recoverApproval(
            Order order,
            String scenario,
            long deadlineNanos,
            boolean allowPostRetry
    ) {
        int postRetries = 0;
        int queryRetries = 0;

        while (retryPolicy.canStartQuery(deadlineNanos)) {
            PaymentResponse response;
            try {
                response = paymentClient.getByOrderId(order.getOrderId());
            } catch (PaymentClientException exception) {
                if (exception.failure() == PaymentClientFailure.PAYMENT_NOT_FOUND) {
                    if (!allowPostRetry
                            || postRetries >= PaymentSyncRetryPolicy.MAX_ADDITIONAL_POST_RETRIES
                            || !retryPolicy.canStartCommand(deadlineNanos)) {
                        return null;
                    }
                    postRetries++;
                    try {
                        response = paymentClient.confirm(
                                confirmRequest(order), scenario
                        );
                    } catch (PaymentClientException postException) {
                        if (!isAmbiguousCommandFailure(postException)) {
                            throw convertClientFailure(order, postException, null);
                        }
                        queryRetries = 0;
                        continue;
                    }
                    if (isFinalApprovalResponse(response)) {
                        return response;
                    }
                    queryRetries = 0;
                    continue;
                }

                if (!isRetryableStateQuery(exception.failure())
                        || queryRetries >= PaymentSyncRetryPolicy.MAX_STATE_QUERY_RETRIES
                        || !retryPolicy.waitBeforeStateQuery(
                        deadlineNanos, queryRetries + 1, order.getOrderId()
                )) {
                    return null;
                }
                queryRetries++;
                continue;
            }

            if (response != null
                    && (response.status() == PaymentServerStatus.COMPLETED
                    || response.status() == PaymentServerStatus.DECLINED
                    || response.status() == PaymentServerStatus.CANCELED)) {
                return response;
            }
            if (queryRetries >= PaymentSyncRetryPolicy.MAX_STATE_QUERY_RETRIES
                    || !retryPolicy.waitBeforeStateQuery(
                    deadlineNanos, queryRetries + 1, order.getOrderId()
            )) {
                return null;
            }
            queryRetries++;
        }
        return null;
    }

    private PaymentResult unresolved(
            Order order,
            PaymentStartResult start,
            String token
    ) {
        markUnknown(
                order.getId(), token,
                OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
        );
        return PaymentResult.processing(
                new PaymentProcessingResult(
                        order.getOrderId(), PaymentApprovalResult.UNKNOWN
                ),
                start.created()
        );
    }

    private PaymentResult requireOperationsReview(
            Order order,
            PaymentStartResult start,
            String token
    ) {
        if (token == null) {
            approvalService.markUnknown(
                    order.getId(), OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
            );
            reconciliationService.markRequired(order.getId());
        } else {
            approvalService.markReview(order.getId(), token);
        }

        log.error(
                "Payment Server 승인 응답이 주문 정보와 일치하지 않습니다. orderId={}",
                order.getOrderId()
        );
        return PaymentResult.processing(
                new PaymentProcessingResult(
                        order.getOrderId(), PaymentApprovalResult.UNKNOWN
                ),
                start.created()
        );
    }

    private PaymentResult complete(
            Order order,
            PaymentStartResult start,
            String token
    ) {
        try {
            PaymentCompletion completion = localCompletionRetry.execute(
                    order.getOrderId(),
                    () -> token == null
                            ? completionService.complete(order.getId())
                            : approvalService.completeWithOwner(
                            order.getId(),
                            token,
                            () -> completionService.complete(order.getId())
                    )
            );
            return PaymentResult.completed(completion, start.created());
        } catch (StalePaymentRecoveryOwnerException exception) {
            return processing(order, start.created());
        } catch (RuntimeException exception) {
            LocalPaymentFailureType failureType =
                    LocalPaymentFailureClassifier.classify(exception);

            try {
                if (token == null) {
                    approvalService.markLocalFailure(order.getId());
                } else {
                    approvalService.markLocalFailure(order.getId(), token);
                }
            } catch (StalePaymentRecoveryOwnerException staleOwner) {
                return processing(order, start.created());
            }

            log.error(
                    "승인 후 로컬 결제 완료 처리에 실패했습니다. orderId={}, failureType={}",
                    order.getOrderId(), failureType, exception
            );

            if (!isRecoverableLocalFailure(failureType)) {
                try {
                    autoCancellationService.cancel(order);
                } catch (RuntimeException cancellationException) {
                    log.error(
                            "승인 후 로컬 처리 실패의 자동 보상 취소 결과를 확정하지 못했습니다. orderId={}",
                            order.getOrderId(), cancellationException
                    );
                    return processing(order, start.created());
                }
                throw exception;
            }
            return processing(order, start.created());
        }
    }

    private void record(
            Long id,
            String token,
            String paymentKey,
            String transactionKey,
            java.time.LocalDateTime approvedAt
    ) {
        if (token == null) {
            approvalService.record(id, paymentKey, transactionKey, approvedAt);
        } else {
            approvalService.record(id, token, paymentKey, transactionKey, approvedAt);
        }
    }

    private void decline(Long id, String token) {
        if (token == null) {
            approvalService.decline(id);
        } else {
            approvalService.decline(id, token);
        }
    }

    private void markUnknown(Long id, String token, OrderFailureCode code) {
        if (token == null) {
            approvalService.markUnknown(id, code);
        } else {
            approvalService.markUnknown(id, token, code);
        }
    }

    private PaymentResult processing(Order order, boolean created) {
        PaymentApprovalResult result = order.getApprovalResult() == null
                ? PaymentApprovalResult.UNKNOWN
                : order.getApprovalResult();
        return PaymentResult.processing(
                new PaymentProcessingResult(order.getOrderId(), result), created
        );
    }

    private boolean isFinalApprovalResponse(PaymentResponse response) {
        return response != null
                && (response.status() == PaymentServerStatus.COMPLETED
                || response.status() == PaymentServerStatus.DECLINED);
    }

    private boolean isAmbiguousCommandFailure(PaymentClientException exception) {
        return exception.outcomeUncertain()
                || exception.failure() == PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                || exception.failure() == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                || exception.failure() == PaymentClientFailure.INVALID_RESPONSE;
    }

    private boolean isRetryableStateQuery(PaymentClientFailure failure) {
        return failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                || failure == PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.INVALID_RESPONSE;
    }

    private RuntimeException convertClientFailure(
            Order order,
            PaymentClientException exception,
            String token
    ) {
        PaymentClientFailure failure = exception.failure();
        if (failure == PaymentClientFailure.PAYMENT_DECLINED) {
            decline(order.getId(), token);
            return new OrderException(OrderErrorCode.PAYMENT_DECLINED, exception);
        }
        if (failure == PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE) {
            markUnknown(
                    order.getId(),
                    token,
                    failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                            ? OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE
                            : OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
            );
            return new OrderException(
                    OrderErrorCode.PAYMENT_RESULT_UNCONFIRMED, exception
            );
        }
        if (failure == PaymentClientFailure.INVALID_REQUEST) {
            decline(order.getId(), token);
            return new OrderException(
                    OrderErrorCode.INVALID_PAYMENT_REQUEST, exception
            );
        }
        if (failure == PaymentClientFailure.AUTHENTICATION_FAILED) {
            decline(order.getId(), token);
            return new OrderException(
                    OrderErrorCode.PAYMENT_PROCESSING_FAILED, exception
            );
        }
        return new OrderException(OrderErrorCode.PAYMENT_PROCESSING_FAILED, exception);
    }

    private boolean isValidCompletedResponse(Order order, PaymentResponse response) {
        return response != null
                && Objects.equals(order.getOrderId(), response.orderId())
                && Objects.equals(order.getAmount(), response.amount())
                && response.status() == PaymentServerStatus.COMPLETED
                && hasText(response.paymentKey())
                && hasText(response.transactionKey())
                && response.approvedAt() != null;
    }

    private PaymentConfirmRequest confirmRequest(Order order) {
        return new PaymentConfirmRequest(order.getOrderId(), order.getAmount());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isRecoverableLocalFailure(LocalPaymentFailureType failureType) {
        return failureType == LocalPaymentFailureType.DEADLOCK
                || failureType == LocalPaymentFailureType.TRANSIENT_INFRASTRUCTURE
                || failureType == LocalPaymentFailureType.UNKNOWN;
    }

    private boolean isAutoCancellation(OrderFailureCode failureCode) {
        return failureCode == OrderFailureCode.AUTO_CANCELLATION_PROCESSING
                || failureCode == OrderFailureCode.AUTO_CANCELLATION_RECONCILIATION_REQUIRED;
    }
}
