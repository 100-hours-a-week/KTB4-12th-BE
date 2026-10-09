package com.gift.gift.infrastructure.ai;

import java.util.Objects;

import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.exception.AiProfilingClientException;
import com.gift.gift.domain.recommendation.exception.AiProfilingFailureType;
import com.gift.gift.global.response.ApiResponse;

@Component
public class AiProfilingClient {

    private static final String PROFILE_PATH =
            "/api/internal/v1/ai/profile/extract-and-pool";

    private final RestClient aiProfilingRestClient;
    private final RestClient aiProfilingHealthRestClient;

    public AiProfilingClient(
            @Qualifier("aiProfilingRestClient")
            RestClient aiProfilingRestClient,
            @Qualifier("aiProfilingHealthRestClient")
            RestClient aiProfilingHealthRestClient
    ) {
        this.aiProfilingRestClient = aiProfilingRestClient;
        this.aiProfilingHealthRestClient = aiProfilingHealthRestClient;
    }

    public boolean isHealthy() {
        try {
            ResponseEntity<JsonNode> response =
                    aiProfilingHealthRestClient.get()
                            .uri("/health")
                            .retrieve()
                            .toEntity(JsonNode.class);

            if (response.getStatusCode().value() != 200
                    || response.getBody() == null) {
                return false;
            }

            JsonNode body = response.getBody();
            JsonNode active = body.path("catalog").path("active");
            JsonNode connected = body.path("store").path("connected");

            return active.isBoolean()
                    && active.booleanValue()
                    && connected.isBoolean()
                    && connected.booleanValue();
        } catch (RuntimeException exception) {
            // health HTTP 오류·연결 실패·파싱 실패는 틱 건너뜀이다.
            return false;
        }
    }

    public AiProfileAcceptedResponse requestProfiling(
            AiProfileRequest request
    ) {
        try {
            ResponseEntity<ApiResponse<AiProfileAcceptedResponse>> response =
                    aiProfilingRestClient.post()
                            .uri(PROFILE_PATH)
                            .body(request)
                            .retrieve()
                            .onStatus(
                                    status -> status.value() == 400,
                                    (httpRequest, httpResponse) -> {
                                        throw failure(
                                                AiProfilingFailureType.INVALID_REQUEST,
                                                "AI가 프로파일링 요청 계약을 거부했습니다."
                                        );
                                    }
                            )
                            .onStatus(
                                    status -> status.value() == 401,
                                    (httpRequest, httpResponse) -> {
                                        throw failure(
                                                AiProfilingFailureType.INVALID_SERVICE_TOKEN,
                                                "AI 서비스 토큰이 올바르지 않습니다."
                                        );
                                    }
                            )
                            .onStatus(
                                    status -> status.value() == 500,
                                    (httpRequest, httpResponse) -> {
                                        throw failure(
                                                AiProfilingFailureType.AI_SERVER_ERROR,
                                                "AI 서버에서 오류가 발생했습니다."
                                        );
                                    }
                            )
                            .onStatus(
                                    status -> status.value() == 503,
                                    (httpRequest, httpResponse) -> {
                                        throw failure(
                                                AiProfilingFailureType.AI_SERVICE_UNAVAILABLE,
                                                "AI 프로파일링 서비스를 사용할 수 없습니다."
                                        );
                                    }
                            )
                            .onStatus(
                                    HttpStatusCode::isError,
                                    (httpRequest, httpResponse) -> {
                                        throw failure(
                                                AiProfilingFailureType.INVALID_RESPONSE,
                                                "정의되지 않은 AI 오류 응답입니다."
                                        );
                                    }
                            )
                            .toEntity(
                                    new ParameterizedTypeReference<
                                            ApiResponse<AiProfileAcceptedResponse>
                                            >() {
                                    }
                            );

            if (response.getStatusCode().value() != 202) {
                throw failure(
                        AiProfilingFailureType.INVALID_RESPONSE,
                        "AI 프로파일링 응답 상태가 202가 아닙니다."
                );
            }

            ApiResponse<AiProfileAcceptedResponse> responseBody =
                    response.getBody();

            if (responseBody == null) {
                throw failure(
                        AiProfilingFailureType.INVALID_RESPONSE,
                        "AI 202 응답 본문이 없습니다."
                );
            }

            AiProfileAcceptedResponse acceptedResponse = responseBody.data();
            validateAcceptedResponse(request, acceptedResponse);
            return acceptedResponse;
        } catch (AiProfilingClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new AiProfilingClientException(
                    AiProfilingFailureType.COMMUNICATION_ERROR,
                    "AI 서버 연결 또는 응답 대기 시간이 초과됐습니다.",
                    exception
            );
        } catch (RestClientException exception) {
            throw new AiProfilingClientException(
                    AiProfilingFailureType.INVALID_RESPONSE,
                    "AI 응답을 처리하지 못했습니다.",
                    exception
            );
        } catch (RuntimeException exception) {
            // 이 범위에는 HTTP·직렬화·응답 처리만 포함된다.
            throw new AiProfilingClientException(
                    AiProfilingFailureType.INVALID_RESPONSE,
                    "AI 요청 직렬화 또는 응답 처리에 실패했습니다.",
                    exception
            );
        }
    }

    private void validateAcceptedResponse(
            AiProfileRequest request,
            AiProfileAcceptedResponse response
    ) {
        if (response == null) {
            throw failure(
                    AiProfilingFailureType.INVALID_RESPONSE,
                    "AI 202 응답 본문에 data가 없습니다."
            );
        }

        boolean valid = Objects.equals(
                response.recipientUserId(),
                request.recipientUserId()
        )
                && response.sourceVersion() == request.sourceVersion()
                && response.profileStatus() == RecipientProfileStatus.PENDING;

        if (!valid) {
            throw failure(
                    AiProfilingFailureType.INVALID_RESPONSE,
                    "AI 202 응답이 요청 정보와 일치하지 않습니다."
            );
        }
    }

    private AiProfilingClientException failure(
            AiProfilingFailureType type,
            String message
    ) {
        return new AiProfilingClientException(type, message);
    }
}
