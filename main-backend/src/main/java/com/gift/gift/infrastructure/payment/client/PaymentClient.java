package com.gift.gift.infrastructure.payment.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.gift.gift.infrastructure.payment.PaymentCommandRetry;
import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Component
public class PaymentClient {

    private static final String CONFIRM_COMMAND = "PAYMENT_CONFIRM";
    private static final String CANCEL_COMMAND = "PAYMENT_CANCEL";

    private final PaymentCommandClient commandClient;
    private final PaymentQueryClient queryClient;
    private final PaymentCommandRetry commandRetry;

    public PaymentClient(
            PaymentCommandClient commandClient,
            PaymentQueryClient queryClient,
            PaymentCommandRetry commandRetry
    ) {
        this.commandClient = commandClient;
        this.queryClient = queryClient;
        this.commandRetry = commandRetry;
    }

    public PaymentResponse confirm(
            PaymentConfirmRequest request,
            String scenario
    ) {
        return commandRetry.execute(
                CONFIRM_COMMAND,
                request.orderId(),
                () -> execute(
                        () -> commandClient.confirm(request, scenario),
                        PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
                ),
                response -> response.status() == PaymentServerStatus.PENDING
        );
    }

    public PaymentCancelResponse cancel(
            String paymentKey,
            String idempotencyKey
    ) {
        return commandRetry.execute(
                CANCEL_COMMAND,
                paymentKey,
                () -> execute(
                        () -> commandClient.cancel(
                                paymentKey,
                                idempotencyKey
                        ),
                        PaymentClientFailure.CANCELLATION_RESULT_UNCONFIRMED
                ),
                response -> false
        );
    }

    public PaymentResponse getByOrderId(String orderId) {
        return execute(
                () -> queryClient.getByOrderId(orderId),
                PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
        );
    }

    private <T> T execute(
            Supplier<PaymentServerResponse<T>> request,
            PaymentClientFailure timeoutFailure
    ) {
        PaymentServerResponse<T> response;

        try {
            response = request.get();
        } catch (PaymentClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            PaymentClientFailure failure = isTimeout(exception)
                    ? timeoutFailure
                    : PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE;

            throw new PaymentClientException(
                    failure,
                    "Payment Server 통신에 실패했습니다.",
                    exception
            );
        } catch (RestClientException exception) {
            throw new PaymentClientException(
                    PaymentClientFailure.INVALID_RESPONSE,
                    "Payment Server 응답을 처리하지 못했습니다.",
                    exception
            );
        }

        if (response == null || response.data() == null) {
            throw new PaymentClientException(
                    PaymentClientFailure.INVALID_RESPONSE,
                    "Payment Server 성공 응답에 data가 없습니다.",
                    (String) null
            );
        }

        return response.data();
    }

    private boolean isTimeout(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}
