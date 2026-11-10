package com.gift.gift.infrastructure.discord;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class DiscordWebhookClientTest {
    private final DiscordWebhookClient client = new DiscordWebhookClient(HttpClient.newHttpClient(), new ObjectMapper());

    @Test
    @DisplayName("wait=true 메시지 ID가 있는 성공만 인정하고 204·잘못된 응답은 재시도한다")
    void requiresCreatedMessageId() {
        assertThat(client.classify(200, null, "{\"id\":\"12345\"}").success()).isTrue();
        assertThat(client.classify(204, null, "").success()).isFalse();
        assertThat(client.classify(200, null, "{}").success()).isFalse();
        assertThat(client.classify(200, null, "{bad").permanent()).isFalse();
        assertThat(client.classify(401, null, "{}").permanent()).isTrue();
        assertThat(client.classify(500, null, "{}").permanent()).isFalse();
        assertThat(client.classify(408, null, "{}").permanent()).isFalse();
    }

    @Test
    @DisplayName("Retry-After 헤더와 retry_after 본문 중 긴 유효 대기를 사용한다")
    void parsesRateLimitWithoutShorteningDelay() {
        var result = client.classify(429, "60.2", "{\"retry_after\":90.001,\"global\":true}");
        assertThat(result.retryAfterMs()).isEqualTo(90001);
        assertThat(result.global()).isTrue();
        assertThat(client.classify(429, "NaN", "{\"retry_after\":-1}").retryAfterMs()).isEqualTo(30000);
        assertThat(client.classify(429, null, "{}").retryAfterMs()).isEqualTo(30000);
    }

    @Test
    @DisplayName("실제 HTTP 요청에 wait=true를 붙이고 429에서 숨은 재시도를 하지 않는다")
    void sendsOnceWithWaitTrue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/webhook", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("wait=true");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"retry_after\":35}".getBytes();
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThat(client.send("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook?wait=false", "{}")
                .retryAfterMs()).isEqualTo(35000);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("본문 전송이 계속 지연돼도 전체 시도는 20초 예산에서 취소된다")
    void timesOutSlowResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/webhook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 100);
            try {
                for (int i = 0; i < 30; i++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            long start = System.nanoTime();
            assertThat(client.send("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", "{}").success())
                .isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(19000L, 25000L);
        } finally {
            server.stop(0);
        }
    }
}
