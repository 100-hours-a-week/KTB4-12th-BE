package com.gift.gift.infrastructure.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gift.gift.infrastructure.payment.client.PaymentCommandClient;
import com.gift.gift.infrastructure.payment.client.PaymentQueryClient;
import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentHttpServiceClientTest {

    private static final String BASE_URL = "http://payment.example.test";
    private static final String API_KEY = "payment-test-api-key";
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private final PaymentClientConfig config = new PaymentClientConfig();

    @Test
    @DisplayName("결제 승인 계약에 맞는 URL·헤더·Body를 전송한다")
    void confirm_sendsContract() {
        RestClient configured = config.paymentCommandRestClient(properties());
        RestClient.Builder builder = configured.mutate();

        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        PaymentCommandClient client = config.paymentCommandClient(builder.build());

        server.expect(requestTo(BASE_URL + "/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(
                        PaymentClientConfig.INTERNAL_API_KEY_HEADER,
                        API_KEY
                ))
                .andExpect(header(PaymentCommandClient.SCENARIO_HEADER, "APPROVE"))
                .andExpect(content().json("""
                        {
                          "orderId": "%s",
                          "amount": 64000
                        }
                        """.formatted(ORDER_ID)))
                .andRespond(withSuccess("""
                        {
                          "message": "결제 요청을 처리했습니다.",
                          "data": {
                            "orderId": "%s",
                            "paymentKey": "payment-key",
                            "transactionKey": "transaction-key",
                            "status": "COMPLETED",
                            "amount": 64000,
                            "approvedAt": "2026-10-13T12:00:00"
                          }
                        }
                        """.formatted(ORDER_ID),
                        MediaType.APPLICATION_JSON));

        PaymentServerResponse<PaymentResponse> response =
                client.confirm(new PaymentConfirmRequest(ORDER_ID, 64000L), "APPROVE");

        assertThat(response.data().status()).isEqualTo(PaymentServerStatus.COMPLETED);
        assertThat(response.data().paymentKey()).isEqualTo("payment-key");

        server.verify();
    }

    @Test
    @DisplayName("주문번호를 결제 상태 조회 경로에 전달한다")
    void getByOrderId_sendsContract() {
        RestClient configured = config.paymentQueryRestClient(properties());
        RestClient.Builder builder = configured.mutate();

        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        PaymentQueryClient client = config.paymentQueryClient(builder.build());

        server.expect(requestTo(
                        BASE_URL + "/payments/orders/" + ORDER_ID
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(PaymentClientConfig.INTERNAL_API_KEY_HEADER, API_KEY))
                .andRespond(withSuccess("""
                        {
                          "message": "결제 상태를 조회했습니다.",
                          "data": {
                            "orderId": "%s",
                            "paymentKey": null,
                            "transactionKey": null,
                            "status": "PENDING",
                            "amount": 64000,
                            "approvedAt": null
                          }
                        }
                        """.formatted(ORDER_ID),
                        MediaType.APPLICATION_JSON));

        PaymentServerResponse<PaymentResponse> response = client.getByOrderId(ORDER_ID);

        assertThat(response.data().orderId()).isEqualTo(ORDER_ID);
        assertThat(response.data().status()).isEqualTo(PaymentServerStatus.PENDING);

        server.verify();
    }

    @Test
    @DisplayName("명령과 조회 RestClient는 서로 다른 응답 타임아웃을 사용한다")
    void clients_useSeparateTimeouts() {
        PaymentClientProperties properties = properties();

        JdkClientHttpRequestFactory commandFactory = requestFactory(config.paymentCommandRestClient(properties));

        JdkClientHttpRequestFactory queryFactory = requestFactory(config.paymentQueryRestClient(properties));

        assertThat(ReflectionTestUtils.getField(
                commandFactory,
                "readTimeout"
        )).isEqualTo(Duration.ofSeconds(3));

        assertThat(ReflectionTestUtils.getField(
                queryFactory,
                "readTimeout"
        )).isEqualTo(Duration.ofSeconds(2));

        HttpClient commandHttpClient =
                (HttpClient) ReflectionTestUtils.getField(commandFactory, "httpClient");

        assertThat(commandHttpClient.connectTimeout()).contains(Duration.ofSeconds(1));
        assertThat(properties.toString()).doesNotContain(API_KEY);
    }

    private JdkClientHttpRequestFactory requestFactory(RestClient restClient) {

        Object requestFactory = ReflectionTestUtils.getField(
                restClient,
                "clientRequestFactory"
        );

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);

        return (JdkClientHttpRequestFactory) requestFactory;
    }

    private PaymentClientProperties properties() {
        return new PaymentClientProperties(
                URI.create(BASE_URL),
                API_KEY,
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                Duration.ofSeconds(2)
        );
    }
}
