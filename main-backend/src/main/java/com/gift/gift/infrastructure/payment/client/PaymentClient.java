package com.gift.gift.infrastructure.payment.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Component
@RequiredArgsConstructor
public class PaymentClient {

    private final PaymentCommandClient commandClient;
    private final PaymentQueryClient queryClient;

    public PaymentResponse confirm(PaymentConfirmRequest request, String scenario
    ) {
        return execute(
                () -> commandClient.confirm(request, scenario),
                PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
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

            throw new PaymentClientException(failure, "Payment Server 통신에 실패했습니다.", exception);
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
