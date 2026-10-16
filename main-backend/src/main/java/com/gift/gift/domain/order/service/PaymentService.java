package com.gift.gift.domain.order.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
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

    private final PaymentRequestService paymentRequestService;
    private final PaymentClient paymentClient;
    private final OrderApprovalTransactionService approvalService;
    private final PaymentCompletionService completionService;

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
        try {
            PaymentResponse response = paymentClient.confirm(
                    confirmRequest(order),
                    scenario
            );

            return response != null && response.status() == PaymentServerStatus.PENDING
                    ? findFinalResult(order)
                    : response;
        } catch (PaymentClientException exception) {
            if (isResultUnconfirmed(exception.failure())) {
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
            PaymentCompletion completion = completionService.complete(order.getId());
            return PaymentResult.completed(completion, created);
        } catch (RuntimeException exception) {
            approvalService.markLocalFailure(order.getId());
            throw exception;
        }
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
            return new OrderException(
                    OrderErrorCode.INVALID_PAYMENT_REQUEST,
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
