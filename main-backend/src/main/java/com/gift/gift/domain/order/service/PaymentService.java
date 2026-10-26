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
    private final OrderApprovalTransactionService approvalService;
    private final PaymentCompletionService completionService;
    private final PaymentLocalCompletionRetry localCompletionRetry;
    private final PaymentAutoCancellationService autoCancellationService;

    public PaymentResult pay(
            Long payerId,
            String idempotencyKey,
            String scenario,
            PaymentCreateRequest request
    ) {
        PaymentStartResult start = paymentRequestService.start(payerId, idempotencyKey, request);

        Order order = start.order();

        if (order.getStatus() == OrderStatus.COMPLETED) {
            return PaymentResult.completed(
                    completionService.findCompleted(order.getId()),
                    false
            );
        }

        if (order.getStatus() == OrderStatus.DECLINED) {
            throw new OrderException(OrderErrorCode.PAYMENT_DECLINED);
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT);
        }

        if (order.getApprovalResult() == PaymentApprovalResult.APPROVED) {
            if (isAutoCancellation(order.getFailureCode())) {
                return processing(order, start.created());
            }

            return complete(order, start.created());
        }

        if (order.getApprovalResult() != null
                && order.getApprovalResult() != PaymentApprovalResult.UNKNOWN) {

            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT
            );
        }

        PaymentResponse response = requestApproval(order, scenario);

        if (response != null && response.status() == PaymentServerStatus.PENDING) {
            approvalService.markUnknown(
                    order.getId(),
                    OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
            );

            return PaymentResult.processing(
                    new PaymentProcessingResult(
                            order.getOrderId(),
                            PaymentApprovalResult.UNKNOWN
                    ),
                    start.created()
            );
        }

        if (response != null && response.status() == PaymentServerStatus.DECLINED) {
            approvalService.decline(order.getId());
            throw new OrderException(OrderErrorCode.PAYMENT_DECLINED);
        }

        validateCompletedResponse(order, response);

        approvalService.record(
                order.getId(),
                response.paymentKey(),
                response.transactionKey(),
                response.approvedAt()
        );

        return complete(order, start.created());
    }

    private PaymentResponse requestApproval(Order order, String scenario) {
        // 승인 요청은 한 번만 전송한다. 진행 중·미확정 재요청은 결과만 조회한다.
        if (!approvalService.claim(order.getId())) {
            return findFinalResult(order);
        }

        try {
            PaymentResponse response = paymentClient.confirm(
                    confirmRequest(order),
                    scenario
            );

            return response != null && response.status() == PaymentServerStatus.PENDING
                    ? findFinalResult(order)
                    : response;
        } catch (PaymentClientException exception) {
            if (isResultUnconfirmed(exception.failure()) || exception.outcomeUncertain()) {
                return findFinalResult(order);
            }

            throw convertClientFailure(order, exception);
        }
    }

    private PaymentResponse findFinalResult(Order order) {
        try {
            return paymentClient.getByOrderId(order.getOrderId());
        } catch (PaymentClientException exception) {
            markUnknown(order, exception.failure());
            throw new OrderException(
                    OrderErrorCode.PAYMENT_RESULT_UNCONFIRMED,
                    exception
            );
        }
    }

    private PaymentResult complete(Order order, boolean created) {
        try {
            PaymentCompletion completion = localCompletionRetry.execute(
                    order.getOrderId(),
                    () -> completionService.complete(order.getId())
            );
            return PaymentResult.completed(completion, created);
        } catch (RuntimeException exception) {
            LocalPaymentFailureType failureType =
                    LocalPaymentFailureClassifier.classify(exception);

            approvalService.markLocalFailure(order.getId());

            log.error(
                    "승인 후 로컬 결제 완료 처리에 실패했습니다. "
                            + "orderId={}, failureType={}",
                    order.getOrderId(),
                    failureType,
                    exception
            );

            if (!isRecoverableLocalFailure(failureType)) {
                try {
                    autoCancellationService.cancel(order);
                } catch (RuntimeException cancellationException) {
                    log.error(
                            "승인 후 로컬 처리 실패의 자동 보상 취소 결과를 확정하지 못했습니다. orderId={}",
                            order.getOrderId(),
                            cancellationException
                    );

                    return processing(order, created);
                }

                throw exception;
            }

            return processing(order, created);
        }
    }

    private PaymentResult processing(Order order, boolean created) {
        return PaymentResult.processing(
                new PaymentProcessingResult(
                        order.getOrderId(),
                        PaymentApprovalResult.APPROVED
                ),
                created
        );
    }

    private boolean isRecoverableLocalFailure(LocalPaymentFailureType failureType) {
        return failureType == LocalPaymentFailureType.DEADLOCK
                || failureType == LocalPaymentFailureType.TRANSIENT_INFRASTRUCTURE
                || failureType == LocalPaymentFailureType.UNKNOWN;
    }

    private boolean isAutoCancellation(OrderFailureCode failureCode) {
        return failureCode == OrderFailureCode.AUTO_CANCELLATION_PROCESSING
                || failureCode
                == OrderFailureCode.AUTO_CANCELLATION_RECONCILIATION_REQUIRED;
    }

    private PaymentConfirmRequest confirmRequest(Order order) {
        return new PaymentConfirmRequest(
                order.getOrderId(),
                order.getAmount()
        );
    }

    private RuntimeException convertClientFailure(Order order, PaymentClientException exception) {

        PaymentClientFailure failure = exception.failure();

        if (failure == PaymentClientFailure.PAYMENT_DECLINED) {
            approvalService.decline(order.getId());

            return new OrderException(
                    OrderErrorCode.PAYMENT_DECLINED,
                    exception
            );
        }

        if (isResultUnconfirmed(failure)) {
            markUnknown(order, failure);
            return new OrderException(
                    OrderErrorCode.PAYMENT_RESULT_UNCONFIRMED,
                    exception
            );
        }

        if (failure == PaymentClientFailure.INVALID_REQUEST) {
            approvalService.decline(order.getId());
            return new OrderException(
                    OrderErrorCode.INVALID_PAYMENT_REQUEST,
                    exception
            );
        }

        if (failure == PaymentClientFailure.AUTHENTICATION_FAILED) {
            approvalService.decline(order.getId());
            return new OrderException(
                    OrderErrorCode.PAYMENT_PROCESSING_FAILED,
                    exception
            );
        }

        return new OrderException(
                OrderErrorCode.PAYMENT_PROCESSING_FAILED,
                exception
        );
    }

    private void markUnknown(Order order, PaymentClientFailure failure) {
        OrderFailureCode failureCode =
                failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                        ? OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE
                        : OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED;

        approvalService.markUnknown(order.getId(), failureCode);
    }

    private boolean isResultUnconfirmed(PaymentClientFailure failure) {
        return failure == PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE;
    }

    private void validateCompletedResponse(Order order, PaymentResponse response) {

        boolean valid = response != null
                        && Objects.equals(order.getOrderId(), response.orderId())
                        && Objects.equals(order.getAmount(), response.amount())
                        && response.status() == PaymentServerStatus.COMPLETED
                        && hasText(response.paymentKey())
                        && hasText(response.transactionKey())
                        && response.approvedAt() != null;

        if (!valid) {
            throw new OrderException(
                    OrderErrorCode.PAYMENT_PROCESSING_FAILED
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
