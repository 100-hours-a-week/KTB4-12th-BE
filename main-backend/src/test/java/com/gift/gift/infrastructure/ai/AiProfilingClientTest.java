package com.gift.gift.infrastructure.ai;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.request.DislikedCategoryRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.exception.AiProfilingClientException;
import com.gift.gift.domain.recommendation.exception.AiProfilingFailureType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AiProfilingClientTest {

    private static final String BASE_URL = "http://ai.example.test";
    private static final String PATH =
            "/api/internal/v1/ai/profile/extract-and-pool";
    private static final String TOKEN = "profiling-service-token";

    private MockRestServiceServer server;
    private AiProfilingClient client;

    @BeforeEach
    void setUp() {
        var properties = properties();
        AiProfilingClientConfig config = new AiProfilingClientConfig();

        RestClient configured = config.aiProfilingRestClient(properties);
        RestClient.Builder builder = configured.mutate();

        server = MockRestServiceServer.bindTo(builder).build();

        client = new AiProfilingClient(
                builder.build(),
                config.aiProfilingHealthRestClient(properties)
        );
    }

    @Test
    @DisplayName("요청 URL·Method·Bearer 토큰·JSON Body를 계약대로 전송한다")
    void requestProfiling_sendsExpectedRequest() {
        server.expect(once(), requestTo(BASE_URL + PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + TOKEN
                ))
                .andExpect(header(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                ))
                .andExpect(content().json("""
                        {
                          "recipientUserId": 10,
                          "sourceVersion": 1,
                          "dislikedCategories": [
                            {
                              "categoryId": 3,
                              "categoryName": "뷰티"
                            }
                          ]
                        }
                        """))
                .andRespond(acceptedResponse());

        client.requestProfiling(request());

        server.verify();
    }

    @Test
    @DisplayName("202 공통 ApiResponse 응답을 AiProfileAcceptedResponse로 역직렬화한다")
    void requestProfiling_deserializesAcceptedResponse() {
        server.expect(requestTo(BASE_URL + PATH))
                .andRespond(acceptedResponse());

        AiProfileAcceptedResponse response =
                client.requestProfiling(request());

        assertThat(response.recipientUserId()).isEqualTo(10L);
        assertThat(response.sourceVersion()).isEqualTo(1L);
        assertThat(response.profileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
    }

    @Test
    @DisplayName("400 응답은 요청 오류이며 틱 중단 대상이 아니다")
    void requestProfiling_maps400() {
        assertFailureMapping(
                HttpStatus.BAD_REQUEST,
                AiProfilingFailureType.INVALID_REQUEST,
                false
        );
    }

    @Test
    @DisplayName("401 응답은 토큰 오류이며 틱 중단 대상이 아니다")
    void requestProfiling_maps401() {
        assertFailureMapping(
                HttpStatus.UNAUTHORIZED,
                AiProfilingFailureType.INVALID_SERVICE_TOKEN,
                false
        );
    }

    @Test
    @DisplayName("500 응답을 재시도 가능한 AI 서버 오류로 매핑한다")
    void requestProfiling_maps500() {
        assertFailureMapping(
                HttpStatus.INTERNAL_SERVER_ERROR,
                AiProfilingFailureType.AI_SERVER_ERROR,
                true
        );
    }

    @Test
    @DisplayName("503 응답을 재시도 가능한 서비스 불가 오류로 매핑한다")
    void requestProfiling_maps503() {
        assertFailureMapping(
                HttpStatus.SERVICE_UNAVAILABLE,
                AiProfilingFailureType.AI_SERVICE_UNAVAILABLE,
                true
        );
    }

    @Test
    @DisplayName("응답 타임아웃을 재시도 가능한 통신 오류로 매핑한다")
    void requestProfiling_mapsTimeout() {
        server.expect(requestTo(BASE_URL + PATH))
                .andRespond(withException(
                        new SocketTimeoutException("read timed out")
                ));

        assertThatThrownBy(() -> client.requestProfiling(request()))
                .isInstanceOfSatisfying(
                        AiProfilingClientException.class,
                        exception -> {
                            assertThat(exception.getFailureType())
                                    .isEqualTo(
                                            AiProfilingFailureType
                                                    .COMMUNICATION_ERROR
                                    );
                            assertThat(exception.isRetryable()).isTrue();
                        }
                );
    }

    @Test
    @DisplayName("Client 설정은 전달받은 연결·읽기 타임아웃과 서비스 토큰을 사용한다")
    void config_buildsClientWithConfiguredValues() {
        var properties = properties();

        RestClient configured = new AiProfilingClientConfig()
                .aiProfilingRestClient(properties);
        Object requestFactory = ReflectionTestUtils.getField(
                configured,
                "clientRequestFactory"
        );

        assertThat(requestFactory)
                .isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(
                requestFactory,
                "connectTimeout"
        )).isEqualTo(100);
        assertThat(ReflectionTestUtils.getField(
                requestFactory,
                "readTimeout"
        )).isEqualTo(200);
        assertThat(properties.toString()).doesNotContain(TOKEN);
    }

    private com.gift.gift.domain.recommendation.support
            .AiProfilingProperties properties() {
        return new com.gift.gift.domain.recommendation.support
                .AiProfilingProperties(
                URI.create(BASE_URL),
                TOKEN,
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofHours(1),
                Duration.ofHours(6),
                10,
                0
        );
    }

    private void assertFailureMapping(
            HttpStatus status,
            AiProfilingFailureType expectedType,
            boolean retryable
    ) {
        server.expect(requestTo(BASE_URL + PATH))
                .andRespond(withStatus(status));

        assertThatThrownBy(() -> client.requestProfiling(request()))
                .isInstanceOfSatisfying(
                        AiProfilingClientException.class,
                        exception -> {
                            assertThat(exception.getFailureType())
                                    .isEqualTo(expectedType);
                            assertThat(exception.isRetryable())
                                    .isEqualTo(retryable);
                        }
                );
    }

    private AiProfileRequest request() {
        return new AiProfileRequest(
                10L,
                1L,
                List.of(new DislikedCategoryRequest(3L, "뷰티"))
        );
    }

    private org.springframework.test.web.client.ResponseCreator
            acceptedResponse() {
        return withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "message": "프로파일 분석이 시작되었습니다.",
                          "data": {
                            "recipientUserId": 10,
                            "sourceVersion": 1,
                            "profileStatus": "PENDING"
                          }
                        }
                        """);
    }
}
