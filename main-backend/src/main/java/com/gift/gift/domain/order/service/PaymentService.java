package com.gift.gift.domain.order.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
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
            return new PaymentResult(completionService.findCompleted(order.getId()), false);
        }

        if (order.getStatus() == OrderStatus.DECLINED) {
            throw new OrderException(OrderErrorCode.PAYMENT_DECLINED);
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderException(OrderErrorCode.PAYMENT_STATE_CONFLICT);
        }

        PaymentResponse response;

        try {
            response = paymentClient.confirm(
                    new PaymentConfirmRequest(
                            order.getOrderId(),
                            order.getAmount()
                    ),
                    scenario
            );
        } catch (PaymentClientException exception) {
            throw convertClientFailure(order, exception);
        }

        handleResponseState(order, response);
        validateCompletedResponse(order, response);

        approvalService.record(
                order.getId(),
                response.paymentKey(),
                response.transactionKey(),
                response.approvedAt()
        );

        try {
            PaymentCompletion completion = completionService.complete(order.getId());

            return new PaymentResult(
                    completion,
                    start.created()
            );
        } catch (RuntimeException exception) {
            approvalService.markLocalFailure(order.getId());
            throw exception;
        }
    }

    private RuntimeException convertClientFailure(Order order, PaymentClientException exception) {

        PaymentClientFailure failure = exception.failure();

        if (failure == PaymentClientFailure.PAYMENT_DECLINED) {
            approvalService.decline(order.getId());

            return new OrderException(OrderErrorCode.PAYMENT_DECLINED, exception);
        }

        if (failure == PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED) {
            approvalService.markUnknown(
                    order.getId(),
                    OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
            );

            return new OrderException(
                    OrderErrorCode.PAYMENT_RESULT_UNCONFIRMED,
                    exception
            );
        }

        if (failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE) {

            approvalService.markUnknown(order.getId(), OrderFailureCode.PAYMENT_SERVER_UNAVAILABLE);

            return new OrderException(
                    OrderErrorCode.PAYMENT_RESULT_UNCONFIRMED,
                    exception
            );
        }

        if (failure == PaymentClientFailure.INVALID_REQUEST) {
            return new OrderException(OrderErrorCode.INVALID_PAYMENT_REQUEST, exception);
        }

        return new OrderException(OrderErrorCode.PAYMENT_PROCESSING_FAILED, exception);
    }

    private void handleResponseState(Order order, PaymentResponse response) {

        if (response.status() == PaymentServerStatus.PENDING) {
            approvalService.markUnknown(
                    order.getId(),
                    OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED
            );

            throw new OrderException(OrderErrorCode.PAYMENT_RESULT_UNCONFIRMED);
        }

        if (response.status() == PaymentServerStatus.DECLINED) {
            approvalService.decline(order.getId());

            throw new OrderException(OrderErrorCode.PAYMENT_DECLINED);
        }
    }

    private void validateCompletedResponse(
            Order order,
            PaymentResponse response
    ) {
        boolean valid =
                response != null
                        && Objects.equals(
                        order.getOrderId(),
                        response.orderId()
                )
                        && Objects.equals(
                        order.getAmount(),
                        response.amount()
                )
                        && response.status()
                        == PaymentServerStatus.COMPLETED
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
