package com.gift.gift.infrastructure.ai.chat;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.*;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.global.exception.BusinessException;

@Slf4j
@Component
public class AiChatClient {
    private final RestClient client;
    private final ObjectMapper mapper;

    public AiChatClient(
            @Qualifier("aiChatRestClient") RestClient client,
            ObjectMapper mapper
    ) {
        this.client = client;
        this.mapper = mapper;
    }

    public record Result<T>(int status, T data) {}

    private record CreateBody(long senderUserId, long recipientUserId) {}

    private record SubmitBody(String message) {
        @Override
        public String toString() {
            return "SubmitBody[REDACTED]";
        }
    }

    public Result<Conversation> create(ChatConversationIdentity identity) {
        var result = call(
                HttpMethod.PUT,
                "/" + identity.conversationId(),
                new CreateBody(identity.senderUserId(), identity.recipientUserId()),
                Conversation.class,
                true
        );

        validate(() -> result.data().validate(identity.conversationId()));
        return result;
    }

    public Conversation conversation(UUID id) {
        var result = call(
                HttpMethod.GET,
                "/" + id,
                null,
                Conversation.class,
                false
        );

        validate(() -> result.data().validate(id));
        return result.data();
    }

    public Result<Turn> submit(UUID id, UUID turnId, String message) {
        var result = call(
                HttpMethod.PUT,
                turnPath(id, turnId),
                new SubmitBody(message),
                Turn.class,
                false
        );

        validate(() -> {
            result.data().validate(turnId);

            if (!message.equals(result.data().message())
                    || (result.status() == 202)
                    != (result.data().status() == Status.PROCESSING)) {
                throw new IllegalArgumentException();
            }
        });

        return result;
    }

    public Turn turn(UUID id, UUID turnId) {
        var result = call(
                HttpMethod.GET,
                turnPath(id, turnId),
                null,
                Turn.class,
                false
        );

        validate(() -> result.data().validate(turnId));
        return result.data();
    }

    public Turn cancel(UUID id, UUID turnId) {
        var result = call(
                HttpMethod.PUT,
                turnPath(id, turnId) + "/cancellation",
                null,
                Turn.class,
                false
        );

        validate(() -> {
            result.data().validate(turnId);

            if (result.data().status() == Status.PROCESSING) {
                throw new IllegalArgumentException();
            }
        });

        return result.data();
    }

    private <T> Result<T> call(
            HttpMethod method,
            String path,
            Object body,
            Class<T> type,
            boolean creation
    ) {
        try {
            var request = client.method(method).uri(path);

            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }

            return request.exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                MediaType contentType = response.getHeaders().getContentType();

                boolean json = contentType != null
                        && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                        || contentType.getSubtype().endsWith("+json"));

                JsonNode envelope = json
                        ? mapper.readTree(response.getBody())
                        : null;

                if (status >= 400) {
                    String upstreamTrace = envelope == null
                            ? null
                            : envelope.path("error").path("traceId").asString(null);

                    log.warn(
                            "AI chat rejected: traceId={}, upstreamTraceId={}, status={}",
                            MDC.get("traceId"),
                            safeTrace(upstreamTrace),
                            status
                    );

                    throw mapped(status, envelope, creation);
                }

                boolean accepted = status == 200
                        || (creation && status == 201)
                        || (!creation && body != null
                        && type == Turn.class && status == 202);

                if (!accepted
                        || envelope == null
                        || !envelope.path("message").isString()
                        || !envelope.path("data").isObject()
                        || envelope.has("error")) {
                    throw unavailable();
                }

                validateScalarTypes(envelope.get("data"), type);

                return new Result<>(
                        status,
                        mapper.treeToValue(envelope.get("data"), type)
                );
            });
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 원문 예외에 본문·토큰이 포함될 수 있으므로 cause도 공개 예외에 붙이지 않는다.
            log.warn(
                    "AI chat transport failed: traceId={}",
                    MDC.get("traceId")
            );
            throw unavailable();
        } catch (RuntimeException exception) {
            log.warn(
                    "AI chat invalid response: traceId={}",
                    MDC.get("traceId")
            );
            throw unavailable();
        }
    }

    private ChatException mapped(
            int status,
            JsonNode envelope,
            boolean creation
    ) {
        String code = envelope == null
                ? null
                : envelope.path("error").path("code").asString(null);

        if (envelope == null || !envelope.path("message").isString()) {
            return unavailable();
        }

        return switch (status) {
            case 400 -> "INVALID_REQUEST".equals(code)
                    ? new ChatException(ChatErrorCode.INVALID_REQUEST)
                    : unavailable();
            case 404 -> "NOT_FOUND".equals(code)
                    ? new ChatException(ChatErrorCode.RESOURCE_NOT_FOUND)
                    : unavailable();
            case 409 -> "CONFLICT".equals(code)
                    ? new ChatException(creation
                            ? ChatErrorCode.CONVERSATION_CONFLICT
                            : ChatErrorCode.TURN_CONFLICT)
                    : unavailable();
            case 413 -> "PAYLOAD_TOO_LARGE".equals(code)
                    ? new ChatException(ChatErrorCode.PAYLOAD_TOO_LARGE)
                    : unavailable();
            case 429 -> "TOO_MANY_REQUESTS".equals(code)
                    ? new ChatException(ChatErrorCode.RATE_LIMIT_EXCEEDED)
                    : unavailable();
            default -> unavailable();
        };
    }

    private void validateScalarTypes(JsonNode data, Class<?> type) {
        if (type == Conversation.class) {
            if (!data.path("conversationId").isString()
                    || !data.path("turns").isArray()) {
                throw unavailable();
            }

            for (JsonNode turn : data.path("turns")) {
                validateScalarTypes(turn, Turn.class);
            }
            return;
        }

        if (!data.path("turnId").isString()
                || !data.path("status").isString()
                || !data.path("message").isString()
                || !data.path("blocks").isArray()
                || !data.path("lastEventId").isIntegralNumber()
                || !data.path("lastEventId").canConvertToLong()
                || (data.has("progressMessage")
                && !data.path("progressMessage").isString())
                || (data.has("error")
                && !data.path("error").path("message").isString())) {
            throw unavailable();
        }

        for (JsonNode block : data.path("blocks")) {
            if (!block.path("type").isString()) {
                throw unavailable();
            }

            if ("text".equals(block.path("type").stringValue())) {
                if (!block.path("text").isString()) {
                    throw unavailable();
                }
            } else {
                if (!block.path("products").isArray()) {
                    throw unavailable();
                }

                for (JsonNode product : block.path("products")) {
                    if (!product.path("productId").isIntegralNumber()
                            || !product.path("productId").canConvertToLong()
                            || !product.path("reason").isString()) {
                        throw unavailable();
                    }
                }
            }
        }
    }

    private void validate(Runnable validation) {
        try {
            validation.run();
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static String safeTrace(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value
                : "unavailable";
    }

    private static String turnPath(UUID id, UUID turnId) {
        return "/" + id + "/turns/" + turnId;
    }

    private static ChatException unavailable() {
        return new ChatException(ChatErrorCode.AI_SERVICE_UNAVAILABLE);
    }
}
