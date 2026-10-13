package com.gift.gift.infrastructure.payment;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.infrastructure.payment.client.PaymentCommandClient;
import com.gift.gift.infrastructure.payment.client.PaymentServerErrorHandler;
import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PaymentServerErrorHandlerTest {

    private static final String BASE_URL = "http://payment.example.test";
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    @DisplayName("Payment Server 거절 응답을 결제 거절 실패 유형으로 변환한다")
    void errorHandler_mapsPaymentDeclined() {
        PaymentCommandClient client = commandClient(
                """
                {
                  "message": "결제가 거절되었습니다.",
                  "error": {
                    "code": "PAYMENT_DECLINED",
                    "traceId": "payment-trace-id"
                  }
                }
                """,
                HttpStatus.UNPROCESSABLE_CONTENT
        );

        assertThatThrownBy(() -> client.confirm(
                new PaymentConfirmRequest(ORDER_ID, 64_000L),
                "DECLINE"
        )).isInstanceOfSatisfying(
                PaymentClientException.class,
                exception -> {
                    assertThat(exception.failure())
                            .isEqualTo(PaymentClientFailure.PAYMENT_DECLINED);
                    assertThat(exception.upstreamTraceId())
                            .isEqualTo("payment-trace-id");
                }
        );
    }

    @Test
    @DisplayName("알 수 없는 오류 응답은 잘못된 응답으로 처리한다")
    void errorHandler_rejectsUnknownError() {
        PaymentCommandClient client = commandClient(
                """
                {
                  "message": "알 수 없는 오류입니다.",
                  "error": {
                    "code": "UNKNOWN_PAYMENT_ERROR",
                    "traceId": "payment-trace-id"
                  }
                }
                """,
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        assertThatThrownBy(() -> client.confirm(
                new PaymentConfirmRequest(ORDER_ID, 64_000L),
                null
        )).isInstanceOfSatisfying(
                PaymentClientException.class,
                exception -> assertThat(exception.failure())
                        .isEqualTo(PaymentClientFailure.INVALID_RESPONSE)
        );
    }

    private PaymentCommandClient commandClient(String responseBody, HttpStatus status) {

        PaymentClientProperties properties =
                new PaymentClientProperties(
                        URI.create(BASE_URL),
                        "payment-test-api-key",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2)
                );

        PaymentClientConfig config = new PaymentClientConfig();

        PaymentServerErrorHandler errorHandler =
                new PaymentServerErrorHandler(new ObjectMapper());

        RestClient configured = config.paymentCommandRestClient(properties, errorHandler);

        RestClient.Builder builder = configured.mutate();

        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(BASE_URL + "/payments/confirm"))
                .andRespond(
                        withStatus(status)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(responseBody)
                );

        return config.paymentCommandClient(builder.build());
    }
}
