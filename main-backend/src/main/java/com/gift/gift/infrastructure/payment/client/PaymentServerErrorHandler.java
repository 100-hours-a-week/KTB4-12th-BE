package com.gift.gift.infrastructure.payment.client;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.infrastructure.payment.dto.PaymentServerErrorResponse;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

public class PaymentServerErrorHandler implements ResponseErrorHandler {

    private final ObjectMapper objectMapper;

    public PaymentServerErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        try {
            PaymentServerErrorResponse errorResponse =
                    objectMapper.readValue(response.getBody(), PaymentServerErrorResponse.class);

            if (errorResponse == null
                    || errorResponse.error() == null
                    || errorResponse.error().code() == null) {
                throw invalidResponse(null);
            }

            PaymentClientFailure failure = PaymentClientFailure.from(errorResponse.error().code());

            if (failure == PaymentClientFailure.INVALID_RESPONSE) {
                throw invalidResponse(null);
            }

            throw new PaymentClientException(
                    failure,
                    errorResponse.message(),
                    errorResponse.error().traceId()
            );
        } catch (PaymentClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse(exception);
        }
    }

    private PaymentClientException invalidResponse(Throwable cause) {

        String message = "Payment Server 오류 응답 형식을 확인할 수 없습니다.";

        if (cause == null) {
            return new PaymentClientException(
                    PaymentClientFailure.INVALID_RESPONSE,
                    message,
                    (String) null
            );
        }

        return new PaymentClientException(
                PaymentClientFailure.INVALID_RESPONSE,
                message,
                cause
        );
    }
}
