package com.gift.gift.infrastructure.ai.chat;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;

@Slf4j
@Component
public class AiChatSseClient {

    private static final int MAX_ERROR_BYTES = 16_384;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final ChatSseProperties properties;
    private final ScheduledExecutorService idleScheduler;

    public AiChatSseClient(
            @Qualifier("aiChatSseRestClient") RestClient client,
            ObjectMapper mapper,
            ChatSseProperties properties,
            @Qualifier("chatSseIdleScheduler")
            ScheduledExecutorService idleScheduler
    ) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
        this.idleScheduler = idleScheduler;
    }

    public Upstream open(
            UUID conversationId,
            UUID turnId,
            long afterEventId
    ) {
        try {
            return client.get()
                    .uri(builder -> builder
                            .path(
                                    "/{conversationId}/turns/{turnId}/events"
                            )
                            .queryParam("afterEventId", afterEventId)
                            .build(conversationId, turnId))
                    .exchangeForRequiredValue((request, response) -> {
                        Upstream upstream = null;
                        InputStream rawBody = null;

                        try {
                            rawBody = response.getBody();

                            upstream = new Upstream(
                                    response,
                                    rawBody,
                                    properties,
                                    idleScheduler
                            );

                            int status =
                                    response.getStatusCode().value();

                            if (status != 200) {
                                throw mapError(
                                        status,
                                        response.getHeaders(),
                                        upstream.body()
                                );
                            }

                            validateStreamResponse(
                                    response.getHeaders()
                            );

                            return upstream;
                        } catch (IOException | RuntimeException exception) {
                            if (upstream != null) {
                                upstream.close();
                            } else {
                                closeResponse(rawBody, response);
                            }

                            throw exception;
                        }
                    }, false);
        } catch (ChatException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "AI SSE connection failed: traceId={}",
                    MDC.get("traceId")
            );

            throw unavailable();
        }
    }

    private void validateStreamResponse(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        String encoding =
                headers.getFirst(HttpHeaders.CONTENT_ENCODING);

        boolean eventStream = type != null
                && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);

        boolean utf8 = type != null
                && (type.getCharset() == null
                || StandardCharsets.UTF_8.equals(type.getCharset()));

        boolean uncompressed = encoding == null
                || "identity".equalsIgnoreCase(encoding);

        if (!eventStream || !utf8 || !uncompressed) {
            throw unavailable();
        }
    }

    private ChatException mapError(
            int status,
            HttpHeaders headers,
            InputStream body
    ) throws IOException {
        MediaType type = headers.getContentType();

        boolean json = type != null
                && (MediaType.APPLICATION_JSON.isCompatibleWith(type)
                || type.getSubtype().endsWith("+json"));

        if (!json) {
            return unavailable();
        }

        byte[] bytes = body.readNBytes(MAX_ERROR_BYTES + 1);

        if (bytes.length > MAX_ERROR_BYTES) {
            return unavailable();
        }

        JsonNode envelope = mapper.readTree(bytes);

        if (envelope == null
                || !envelope.path("message").isString()
                || !envelope.path("error").isObject()
                || envelope.has("data")) {
            return unavailable();
        }

        String code = envelope.path("error")
                .path("code")
                .asString("");

        log.warn(
                "AI SSE rejected: traceId={}, status={}",
                MDC.get("traceId"),
                status
        );

        return switch (status) {
            case 400 -> mapBadRequest(code);

            case 404 -> "NOT_FOUND".equals(code)
                    ? new ChatException(
                    ChatErrorCode.RESOURCE_NOT_FOUND
            )
                    : unavailable();

            case 409 -> "CONFLICT".equals(code)
                    ? new ChatException(
                    ChatErrorCode.EVENT_CURSOR_CONFLICT
            )
                    : unavailable();

            case 429 -> "TOO_MANY_REQUESTS".equals(code)
                    ? new ChatException(
                    ChatErrorCode.RATE_LIMIT_EXCEEDED
            )
                    : unavailable();

            default -> unavailable();
        };
    }

    private ChatException mapBadRequest(String code) {
        return switch (code) {
            case "INVALID_CURSOR" -> new ChatException(
                    ChatErrorCode.INVALID_EVENT_CURSOR
            );

            case "INVALID_REQUEST" -> new ChatException(
                    ChatErrorCode.INVALID_REQUEST
            );

            default -> unavailable();
        };
    }

    private static ChatException unavailable() {
        return new ChatException(
                ChatErrorCode.AI_SERVICE_UNAVAILABLE
        );
    }

    private static void closeResponse(
            InputStream body,
            ClientHttpResponse response
    ) {
        try {
            // 응답 close가 남은 본문을 읽지 않도록 본문을 먼저 닫는다.
            if (body != null) {
                body.close();
            }
        } catch (IOException ignored) {
            // 정리 오류의 원문에는 외부 연결 정보가 포함될 수 있다.
        } finally {
            response.close();
        }
    }

    public static final class Upstream implements AutoCloseable {

        private final ClientHttpResponse response;
        private final InputStream rawBody;
        private final InputStream body;

        private final AtomicBoolean closed = new AtomicBoolean();

        private volatile long lastReadNanos = System.nanoTime();
        private volatile ScheduledFuture<?> idleTask;

        private Upstream(
                ClientHttpResponse response,
                InputStream rawBody,
                ChatSseProperties properties,
                ScheduledExecutorService scheduler
        ) {
            this.response = response;
            this.rawBody = rawBody;

            this.body = new FilterInputStream(rawBody) {
                @Override
                public int read() throws IOException {
                    int value = in.read();

                    if (value >= 0) {
                        lastReadNanos = System.nanoTime();
                    }

                    return value;
                }

                @Override
                public int read(
                        byte[] bytes,
                        int offset,
                        int length
                ) throws IOException {
                    int count = in.read(bytes, offset, length);

                    if (count > 0) {
                        lastReadNanos = System.nanoTime();
                    }

                    return count;
                }

                @Override
                public void close() {
                    Upstream.this.close();
                }
            };

            long idleNanos =
                    properties.idleTimeout().toNanos();

            long intervalMillis = Math.min(
                    1000,
                    properties.idleTimeout().toMillis()
            );

            idleTask = scheduler.scheduleAtFixedRate(() -> {
                long elapsed =
                        System.nanoTime() - lastReadNanos;

                if (elapsed >= idleNanos) {
                    close();
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);

            if (closed.get()) {
                idleTask.cancel(false);
            }
        }

        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            ScheduledFuture<?> task = idleTask;

            if (task != null) {
                task.cancel(false);
            }

            closeResponse(rawBody, response);
        }
    }
}
