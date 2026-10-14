package com.gift.gift.domain.recommendation.chat.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.recommendation.chat.config.ChatSseConfig;
import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.domain.recommendation.chat.service.ChatConversationAccessService;
import com.gift.gift.domain.recommendation.chat.service.ChatSseService;
import com.gift.gift.domain.recommendation.chat.web.ChatBodyAdvice;
import com.gift.gift.domain.recommendation.chat.web.ChatSseRelay;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.CurrentUserId;
import com.gift.gift.infrastructure.ai.chat.AiChatProperties;
import com.gift.gift.infrastructure.ai.chat.AiChatSseClient;
import com.gift.gift.infrastructure.ai.chat.AiChatSseClientConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(15)
class ChatSseTransportTest {
    @TempDir
    Path temporaryDirectory;
    private static final String ID = "22222222-2222-4222-8222-222222222222";
    private static final String TURN = "11111111-1111-4111-8111-111111111111";
    private static ChatSseService currentService;
    private static ChatSseProperties currentProperties;
    private HttpServer ai;
    private ExecutorService aiExecutor;
    private ExecutorService relayExecutor;
    private HttpClient sseHttpClient;
    private ScheduledExecutorService idleScheduler;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext context;
    private ChatConversationAccessService access;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger cancellationCalls = new AtomicInteger();
    private final AtomicReference<String> aiAuthorization = new AtomicReference<>();
    private final AtomicReference<String> aiConversationToken = new AtomicReference<>();
    private String endpoint;
    private int upstreamStatus;
    private volatile java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> stream;

    @BeforeEach
    void setUp() throws Exception {
        currentProperties = new ChatSseProperties(Duration.ofMillis(500), Duration.ofSeconds(240), 1);
        upstreamStatus = 200;
        stream = exchange -> {};
        ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        aiExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ai.setExecutor(aiExecutor);
        ai.createContext("/v1/chat/conversations", exchange -> {
            calls.incrementAndGet();
            if (exchange.getRequestURI().getPath().endsWith("/cancellation")) {
                cancellationCalls.incrementAndGet();
            }
            aiAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            aiConversationToken.set(exchange.getRequestHeaders().getFirst("X-Conversation-Token"));
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", upstreamStatus == 200
                        ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(upstreamStatus, 0);
                stream.accept(exchange);
            }
        });
        ai.start();
        AiChatProperties clientProperties = new AiChatProperties(
                URI.create("http://127.0.0.1:" + ai.getAddress().getPort() + "/v1/chat/conversations"),
                "private-service-token", Duration.ofSeconds(5), Duration.ofSeconds(15));
        AiChatSseClientConfig sseConfig = new AiChatSseClientConfig();
        sseHttpClient = sseConfig.aiChatSseHttpClient(clientProperties);
        idleScheduler = sseConfig.chatSseIdleScheduler();
        AiChatSseClient client = new AiChatSseClient(
                sseConfig.aiChatSseRestClient(clientProperties, currentProperties, sseHttpClient),
                JsonMapper.builder().build(), currentProperties, idleScheduler);
        access = mock(ChatConversationAccessService.class);
        when(access.authorize(null, ID, "conversation-test"))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        when(access.authorize(1L, ID, "conversation-test"))
                .thenReturn(new ChatConversationIdentity(UUID.fromString(ID), 1, 2));
        relayExecutor = new ChatSseConfig().chatSseExecutor();
        currentService = new ChatSseService(access, client, currentProperties, relayExecutor);
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createDirectory(temporaryDirectory.resolve("tomcat")).toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context web = tomcat.addContext("", Files.createDirectory(temporaryDirectory.resolve("web")).toString());
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(web.getServletContext());
        context.register(WebConfig.class);
        Wrapper servlet = Tomcat.addServlet(web, "api", new DispatcherServlet(context));
        servlet.setAsyncSupported(true);
        servlet.setLoadOnStartup(1);
        web.addServletMappingDecoded("/", "api");
        tomcat.start();
        endpoint = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort()
                + "/recommendations/conversations/" + ID + "/turns/" + TURN + "/events";
    }

    @AfterEach
    void tearDown() throws Exception {
        currentService.shutdown();
        ai.stop(0);
        idleScheduler.shutdownNow();
        sseHttpClient.shutdownNow();
        relayExecutor.shutdownNow();
        aiExecutor.shutdownNow();
        tomcat.stop();
        tomcat.destroy();
        context.close();
    }

    @Test
    @DisplayName("첫 이벤트를 생성 완료 전에 전달하고 원문·서비스 인증·헤더를 보존한다")
    void relay_deliversBeforeFinishedWithoutExposingTokens() throws Exception {
        String first = ": keep-alive\r\n\r\nid: 1\r\nevent: answer.delta\r\ndata: {\"delta\":\"선물 🎁\"}\r\n\r\n";
        String last = "id: 2\nevent: turn.finished\ndata: {\"status\":\"COMPLETED\"}\n\n";
        CountDownLatch finish = new CountDownLatch(1);
        stream = exchange -> {
            write(exchange, first);
            try {
                if (finish.await(3, TimeUnit.SECONDS)) { write(exchange, last); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        HttpURLConnection response = request("?afterEventId=0");
        try (InputStream body = response.getInputStream()) {
            assertThat(response.getHeaderField("Cache-Control")).isEqualTo("no-store, no-transform");
            assertThat(response.getHeaderField("X-Accel-Buffering")).isEqualTo("no");
            assertThat(response.getContentType()).startsWith("text/event-stream");
            assertThat(new String(body.readNBytes(first.getBytes(StandardCharsets.UTF_8).length), StandardCharsets.UTF_8))
                    .isEqualTo(first);
            assertThat(finish.getCount()).isEqualTo(1);
            finish.countDown();
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(last);
        } finally { finish.countDown(); response.disconnect(); }
        assertThat(aiAuthorization.get()).isEqualTo("Bearer private-service-token");
        assertThat(aiConversationToken.get()).isNull();
        assertThat(response.getHeaderFields().toString()).doesNotContain("private-service-token");
        awaitCleanup();
        assertThat(cancellationCalls.get()).isZero();
    }

    @Test
    @DisplayName("heartbeat를 계속 받으면 idle 시간이 초기화되고 ID를 추가하지 않는다")
    void relay_resetsIdleOnHeartbeats() throws Exception {
        stream = exchange -> {
            for (int i = 0; i < 5; i++) {
                write(exchange, ": keep-alive\n\n");
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        HttpURLConnection response = request("");
        assertThat(new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(": keep-alive\n\n".repeat(5));
        response.disconnect();
        awaitCleanup();
    }

    @Test
    @DisplayName("cursor를 AI에 전달하고 종료 턴의 마지막 ID에서는 빈 스트림을 반환한다")
    void relay_reconnectsAndAcceptsEmptyTerminalStream() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        stream = exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            if ("afterEventId=1".equals(query.get())) {
                write(exchange, "id: 2\nevent: turn.finished\ndata: {}\n\n");
            }
        };
        HttpURLConnection response = request("?afterEventId=1");
        assertThat(new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).startsWith("id: 2\n");
        response.disconnect();
        awaitCleanup();
        response = request("?afterEventId=2");
        assertThat(response.getResponseCode()).isEqualTo(200);
        assertThat(response.getInputStream().readAllBytes()).isEmpty();
        assertThat(query.get()).isEqualTo("afterEventId=2");
        response.disconnect();
        awaitCleanup();
    }

    @Test
    @DisplayName("AI 409는 스트림 시작 전에 공통 JSON으로 반환한다")
    void relay_returnsJsonBeforeStreamForFutureCursor() throws Exception {
        upstreamStatus = 409;
        stream = exchange -> write(exchange, "{\"message\":\"conflict\",\"error\":{\"code\":\"CONFLICT\"}}");
        HttpURLConnection response = request("?afterEventId=999");
        assertThat(response.getResponseCode()).isEqualTo(409);
        assertThat(response.getContentType()).startsWith("application/json");
        String body = new String(response.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).contains("CONFLICT", "traceId", "이벤트 번호가 저장된 마지막 번호보다 큽니다.")
                .doesNotContain("private-service-token", "details");
        response.disconnect();
        awaitCleanup();
    }

    @Test
    @DisplayName("권한 오류와 잘못된 cursor·중복 Query는 AI 연결 전에 차단한다")
    void relay_rejectsUnauthorizedAndInvalidInputBeforeUpstream() throws Exception {
        when(access.authorize(1L, ID, "wrong")).thenThrow(new ChatException(ChatErrorCode.RESOURCE_NOT_FOUND));
        for (String suffix : List.of("?afterEventId=-1", "?afterEventId=0&afterEventId=1", "?unknown=1")) {
            HttpURLConnection response = request(suffix);
            assertThat(response.getResponseCode()).isEqualTo(400);
            assertThat(response.getContentType()).startsWith("application/json");
            response.disconnect();
        }
        HttpURLConnection response = request("");
        response.setRequestProperty("X-Conversation-Token", "wrong");
        assertThat(response.getResponseCode()).isEqualTo(404);
        response.disconnect();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("idle 만료·단순 EOF는 종료 이벤트를 만들지 않고 자원을 정리한다")
    void relay_closesOnIdleWithoutInventingFinishedEvent() throws Exception {
        stream = exchange -> {
            write(exchange, ": keep-alive\n\n");
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        HttpURLConnection response = request("");
        assertThat(new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(": keep-alive\n\n");
        response.disconnect();
        awaitCleanup();
        assertThat(cancellationCalls.get()).isZero();
    }

    @Test
    @DisplayName("FE 연결 종료 후 upstream·작업·슬롯을 정리하고 cancellation을 보내지 않는다")
    void relay_cleansUpDisconnectedFrontend() throws Exception {
        stream = exchange -> {
            for (int i = 0; i < 100; i++) {
                try {
                    exchange.getResponseBody().write((": " + "x".repeat(4096) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    Thread.sleep(10);
                } catch (IOException | InterruptedException e) { return; }
            }
        };
        HttpURLConnection response = request("");
        InputStream body = response.getInputStream();
        body.readNBytes(10);
        response.disconnect();
        body.close();
        awaitCleanup();
        assertThat(cancellationCalls.get()).isZero();
    }

    @Test
    @DisplayName("연결 상한 초과는 JSON 429이며 기존 연결 종료 후 슬롯을 재사용한다")
    void relay_enforcesConnectionLimit() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        stream = exchange -> {
            write(exchange, ": keep-alive\n\n");
            try { finish.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        HttpURLConnection first = request("");
        first.getInputStream().readNBytes(14);
        try {
            HttpURLConnection second = request("");
            assertThat(second.getResponseCode()).isEqualTo(429);
            assertThat(second.getContentType()).startsWith("application/json");
            second.disconnect();
            assertThat(calls.get()).isEqualTo(1);
        } finally { finish.countDown(); first.disconnect(); }
        awaitCleanup();
        stream = exchange -> {};
        HttpURLConnection next = request("");
        assertThat(next.getResponseCode()).isEqualTo(200);
        next.getInputStream().readAllBytes();
        next.disconnect();
        awaitCleanup();
    }

    @Test
    @DisplayName("미로그인과 잘못된 turn Path는 SSE 시작 전에 JSON으로 거절한다")
    void relay_rejectsMissingLoginAndInvalidTurn() throws Exception {
        HttpURLConnection missingLogin = request("");
        missingLogin.setRequestProperty("Authorization", "");
        try {
            assertThat(missingLogin.getResponseCode()).isEqualTo(401);
            assertThat(missingLogin.getContentType()).startsWith("application/json");
        } finally {
            missingLogin.disconnect();
        }
        String validEndpoint = endpoint;
        endpoint = endpoint.replace(TURN, "not-a-uuid");
        HttpURLConnection invalidTurn = request("");
        try {
            assertThat(invalidTurn.getResponseCode()).isEqualTo(400);
            assertThat(invalidTurn.getContentType()).startsWith("application/json");
        } finally {
            invalidTurn.disconnect();
            endpoint = validEndpoint;
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("AI 접속 오류는 JSON 503이며 실패한 연결의 슬롯을 반환한다")
    void relay_releasesCapacityAfterUpstreamFailure() throws Exception {
        upstreamStatus = 503;
        stream = exchange -> write(exchange,
                "{\"message\":\"private-service-token\",\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}");
        HttpURLConnection failed = request("");
        try {
            assertThat(failed.getResponseCode()).isEqualTo(503);
            assertThat(failed.getContentType()).startsWith("application/json");
            try (InputStream body = failed.getErrorStream()) {
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("traceId").doesNotContain("private-service-token");
            }
        } finally {
            failed.disconnect();
        }
        awaitCleanup();
        upstreamStatus = 200;
        stream = exchange -> {};
        HttpURLConnection next = request("");
        try (InputStream body = next.getInputStream()) {
            assertThat(next.getResponseCode()).isEqualTo(200);
            assertThat(body.readAllBytes()).isEmpty();
        } finally {
            next.disconnect();
        }
        awaitCleanup();
    }

    @Test
    @DisplayName("AI가 종료 이벤트 없이 EOF를 보내도 finished를 추가하지 않는다")
    void relay_preservesUnexpectedUpstreamEof() throws Exception {
        String partial = "id: 1\nevent: answer.delta\ndata: {\"delta\":\"partial\"}\n\n";
        stream = exchange -> write(exchange, partial);
        HttpURLConnection response = request("");
        try (InputStream body = response.getInputStream()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(partial);
        } finally {
            response.disconnect();
        }
        awaitCleanup();
        assertThat(cancellationCalls.get()).isZero();
    }

    private HttpURLConnection request(String suffix) throws Exception {
        HttpURLConnection response = (HttpURLConnection) URI.create(endpoint + suffix).toURL().openConnection();
        response.setReadTimeout(3000);
        response.setRequestProperty("Accept", "text/event-stream");
        response.setRequestProperty("Authorization", "Bearer login-test");
        response.setRequestProperty("X-Conversation-Token", "conversation-test");
        return response;
    }

    private void awaitCleanup() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (currentService.activeConnections() != 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertThat(currentService.activeConnections()).isZero();
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, String value) {
        try {
            exchange.getResponseBody().write(value.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        } catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }

    @Configuration
    @EnableWebMvc
    static class WebConfig implements WebMvcConfigurer {
        @Bean ChatEventsController controller() {
            return new ChatEventsController(currentService, new ChatSseRelay(currentService, currentProperties));
        }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean ChatBodyAdvice bodyAdvice() { return new ChatBodyAdvice(); }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            // transport 검증용 로그인 stub. 실제 JWT·소유권 정책은 기존 보안 테스트에서 확인한다.
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(CurrentUserId.class);
                }
                @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                        NativeWebRequest request, WebDataBinderFactory binder) {
                    return "Bearer login-test".equals(request.getHeader("Authorization")) ? 1L : null;
                }
            });
        }
    }
}
