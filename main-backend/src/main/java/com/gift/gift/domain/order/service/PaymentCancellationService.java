package com.gift.gift.domain.order.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.recovery.StalePaymentRecoveryOwnerException;
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
    private final PaymentCancellationCompensationService compensationService;
    private final PaymentCancellationTransactionService transactionService;

    public PaymentCancellationApproval request(
            Long requesterId,
            String orderId,
            String idempotencyKey,
            CancellationSource source
    ) {
        PaymentCancellationStartResult start = requestService.start(
                requesterId, orderId, idempotencyKey, source
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
            throw new OrderException(
                    OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED
            );
        }

        if (source == CancellationSource.USER
                && start.recoveryTaskManaged()
                && start.syncOwnerToken() == null) {
            throw new OrderException(
                    OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED
            );
        }

        String token = start.syncOwnerToken();
        boolean sendRequest = source != CancellationSource.USER
                || token != null
                || (!start.recoveryTaskManaged()
                && transactionService.claimUserRequest(cancellation.getId()));

        PaymentCancelResponse response = sendRequest
                ? requestCancellation(cancellation, order, token)
                : recoverCancellation(cancellation, order, token);

        validateResponse(order, response);

        return new PaymentCancellationApproval(
                cancellation,
                order,
                response.transactionKey(),
                response.cancelAmount(),
                response.canceledAt(),
                start.created(),
                start.recoveryTaskManaged(),
                token
        );
    }

    public PaymentCancellationResult cancel(
            Long requesterId,
            String orderId,
            String idempotencyKey,
            CancellationSource source
    ) {
        PaymentCancellationApproval approval = request(
                requesterId, orderId, idempotencyKey, source
        );
        Long cancellationId = approval.cancellation().getId();
        String token = approval.syncOwnerToken();

        try {
            if (token == null) {
                transactionService.recordPaymentServerCancellationResult(
                        cancellationId,
                        approval.transactionKey(),
                        approval.cancelAmount(),
                        approval.canceledAt()
                );
                return compensationService.compensate(approval);
            }

            transactionService.recordPaymentServerCancellationResult(
                    cancellationId,
                    token,
                    approval.transactionKey(),
                    approval.cancelAmount(),
                    approval.canceledAt()
            );
            return transactionService.completeWithOwner(
                    approval,
                    () -> compensationService.compensate(approval)
            );
        } catch (StalePaymentRecoveryOwnerException exception) {
            throw new OrderException(
                    OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED, exception
            );
        } catch (RuntimeException exception) {
            if (token == null) {
                transactionService.markLocalFailure(cancellationId);
            } else {
                transactionService.markLocalFailure(cancellationId, token);
            }
            throw exception;
        }
    }

    private PaymentCancelResponse requestCancellation(
            PaymentCancellation cancellation,
            Order order,
            String token
    ) {
        try {
            return paymentClient.cancel(
                    order.getPaymentKey(), cancellation.getIdempotencyKey()
            );
        } catch (PaymentClientException exception) {
            if (isResultUnconfirmed(exception.failure())
                    || exception.outcomeUncertain()) {
                return recoverCancellation(cancellation, order, token);
            }

            boolean cancellationDeclined =
                    exception.failure() == PaymentClientFailure.CANCELLATION_DECLINED
                            && cancellation.getCancellationSource()
                            != CancellationSource.LOCAL_PROCESSING_FAILURE;
            boolean userRequestRejected =
                    cancellation.getCancellationSource() == CancellationSource.USER
                            && (exception.failure()
                            == PaymentClientFailure.AUTHENTICATION_FAILED
                            || exception.failure()
                            == PaymentClientFailure.INVALID_REQUEST);

            if (cancellationDeclined || userRequestRejected) {
                if (token == null) {
                    transactionService.markDeclined(cancellation.getId());
                } else {
                    transactionService.markDeclined(cancellation.getId(), token);
                }
            }
            throw convertClientFailure(exception);
        }
    }

    private PaymentCancelResponse recoverCancellation(
            PaymentCancellation cancellation,
            Order order,
            String token
    ) {
        PaymentCancelResponse response;
        try {
            response = paymentClient.getCancellation(
                    order.getPaymentKey(), cancellation.getIdempotencyKey()
            );
        } catch (PaymentClientException exception) {
            markResultUnconfirmed(cancellation, exception.failure(), token);
            throw new OrderException(
                    OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED, exception
            );
        }

        if (!isValidResponse(order, response)) {
            markResultUnconfirmed(
                    cancellation,
                    PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED,
                    token
            );
            throw new OrderException(
                    OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED
            );
        }
        return response;
    }

    private PaymentCancellationApproval completed(
            PaymentCancellationStartResult start
    ) {
        PaymentCancellation cancellation = start.cancellation();
        return new PaymentCancellationApproval(
                cancellation,
                start.order(),
                cancellation.getCancelTransactionKey(),
                cancellation.getCancelAmount(),
                cancellation.getCanceledAt(),
                false,
                start.recoveryTaskManaged(),
                start.syncOwnerToken()
        );
    }

    private RuntimeException convertClientFailure(
            PaymentClientException exception
    ) {
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
        if (isResultUnconfirmed(failure)
                || failure == PaymentClientFailure.PAYMENT_NOT_FOUND) {
            return new OrderException(
                    OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED, exception
            );
        }
        return new OrderException(
                OrderErrorCode.CANCELLATION_PROCESSING_FAILED, exception
        );
    }

    private void markResultUnconfirmed(
            PaymentCancellation cancellation,
            PaymentClientFailure failure,
            String token
    ) {
        PaymentCancellationFailureCode code =
                failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                        ? PaymentCancellationFailureCode.PAYMENT_SERVER_UNAVAILABLE
                        : PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED;

        if (token == null) {
            transactionService.markResultUnconfirmed(cancellation.getId(), code);
        } else {
            transactionService.markResultUnconfirmed(
                    cancellation.getId(), token, code
            );
        }
    }

    private boolean isResultUnconfirmed(PaymentClientFailure failure) {
        return failure == PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED
                || failure == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE;
    }

    private void validateResponse(Order order, PaymentCancelResponse response) {
        if (!isValidResponse(order, response)) {
            throw new OrderException(
                    OrderErrorCode.CANCELLATION_PROCESSING_FAILED
            );
        }
    }

    private boolean isValidResponse(Order order, PaymentCancelResponse response) {
        return response != null
                && Objects.equals(order.getOrderId(), response.orderId())
                && Objects.equals(order.getPaymentKey(), response.paymentKey())
                && Objects.equals(order.getAmount(), response.cancelAmount())
                && response.status() == PaymentServerStatus.CANCELED
                && hasText(response.transactionKey())
                && response.canceledAt() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
