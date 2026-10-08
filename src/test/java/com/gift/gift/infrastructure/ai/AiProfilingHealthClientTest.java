package com.gift.gift.infrastructure.ai;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.recommendation.support.AiProfilingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiProfilingHealthClientTest {

    private static final String BASE_URL = "http://ai.example.test";

    private MockRestServiceServer server;
    private AiProfilingClient client;

    @BeforeEach
    void setUp() {
        AiProfilingProperties properties = new AiProfilingProperties(
                URI.create("http://ai.example.test"),
                "test-token",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofHours(6),
                100,
                0
        );

        AiProfilingClientConfig config = new AiProfilingClientConfig();

        RestClient.Builder health =
                config.aiProfilingHealthRestClient(properties).mutate();

        server = MockRestServiceServer.bindTo(health).build();

        client = new AiProfilingClient(
                config.aiProfilingRestClient(properties),
                health.build()
        );
    }

    @Test
    @DisplayName("무인증 GET health의 200과 두 true 필드가 정상 판정 조건이다")
    void health_requires200AndBothFlagsWithoutToken() {
        server.expect(requestTo(BASE_URL + "/health"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(
                        request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)
                ).isNull())
                .andRespond(withSuccess("""
                        {
                          "catalog": {"active": true},
                          "store": {"connected": true}
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.isHealthy()).isTrue();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"catalog\":{\"active\":false},\"store\":{\"connected\":true}}",
            "{\"catalog\":{\"active\":true},\"store\":{\"connected\":false}}",
            "{}",
            "{\"catalog\":{\"active\":\"true\"},\"store\":{\"connected\":true}}",
            "invalid-json"
    })
    @DisplayName("health 필드 false·누락·타입 오류·파싱 실패는 비정상이다")
    void health_rejectsInvalidBody(String body) {
        server.expect(requestTo(BASE_URL + "/health"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.isHealthy()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 204, 400, 401, 500, 503})
    @DisplayName("health는 200 이외 상태를 비정상으로 판정한다")
    void health_rejectsNon200(int status) {
        server.expect(requestTo(BASE_URL + "/health"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("health 연결·응답 실패는 예외 대신 비정상을 반환한다")
    void health_handlesConnectionFailure() {
        server.expect(requestTo(BASE_URL + "/health"))
                .andRespond(withException(
                        new SocketTimeoutException("timeout")
                ));

        assertThat(client.isHealthy()).isFalse();
    }
}
