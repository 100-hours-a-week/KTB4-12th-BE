package com.gift.gift.domain.order.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Service
@RequiredArgsConstructor
public class PaymentCancellationService {

    private final PaymentCancellationRequestService requestService;
    private final PaymentClient paymentClient;

    public PaymentCancellationApproval request(
            Long requesterId,
            String orderId,
            String idempotencyKey,
            CancellationSource source
    ) {
        PaymentCancellationStartResult start =
                requestService.start(
                        requesterId,
                        orderId,
                        idempotencyKey,
                        source
                );

        PaymentCancellation cancellation = start.cancellation();
        Order order = start.order();

        if (cancellation.getStatus() == PaymentCancellationStatus.COMPLETED) {
            return completed(start);
        }

        if (cancellation.getStatus() == PaymentCancellationStatus.DECLINED) {
            throw new OrderException(OrderErrorCode.CANCELLATION_DECLINED);
        }

        if (cancellation.getStatus() == PaymentCancellationStatus.FAILED) {
            throw new OrderException(OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED);
        }

        PaymentCancelResponse response;

        try {
            response = paymentClient.cancel(
                    order.getPaymentKey(),
                    cancellation.getIdempotencyKey()
            );
        } catch (PaymentClientException exception) {
            throw convertClientFailure(exception);
        }

        validateResponse(order, response);

        return new PaymentCancellationApproval(
                cancellation,
                order,
                response.transactionKey(),
                response.cancelAmount(),
                response.canceledAt(),
                start.created()
        );
    }

    private PaymentCancellationApproval completed(PaymentCancellationStartResult start) {

        PaymentCancellation cancellation = start.cancellation();

        return new PaymentCancellationApproval(
                cancellation,
                start.order(),
                cancellation.getCancelTransactionKey(),
                cancellation.getCancelAmount(),
                cancellation.getCanceledAt(),
                false
        );
    }

    private RuntimeException convertClientFailure(PaymentClientException exception) {

        PaymentClientFailure failure = exception.failure();

        if (failure == PaymentClientFailure.IDEMPOTENCY_KEY_CONFLICT) {
            return new OrderException(OrderErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
        }

        if (failure == PaymentClientFailure.PAYMENT_NOT_CANCELABLE) {
            return new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE, exception);
        }

        if (failure == PaymentClientFailure.CANCELLATION_DECLINED) {
            return new OrderException(OrderErrorCode.CANCELLATION_DECLINED, exception);
        }

        if (failure == PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                || failure == PaymentClientFailure.PAYMENT_NOT_FOUND) {

            return new OrderException(OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED, exception);
        }

        return new OrderException(OrderErrorCode.CANCELLATION_PROCESSING_FAILED, exception);
    }

    private void validateResponse(Order order, PaymentCancelResponse response) {

        boolean valid = response != null
                && Objects.equals(order.getOrderId(), response.orderId())
                && Objects.equals(order.getPaymentKey(), response.paymentKey())
                && Objects.equals(order.getAmount(), response.cancelAmount())
                && response.status() == PaymentServerStatus.CANCELED
                && hasText(response.transactionKey())
                && response.canceledAt() != null;

        if (!valid) {
            throw new OrderException(OrderErrorCode.CANCELLATION_PROCESSING_FAILED);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
