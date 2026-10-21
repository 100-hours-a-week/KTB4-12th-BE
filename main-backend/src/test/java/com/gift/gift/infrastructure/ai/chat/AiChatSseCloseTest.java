package com.gift.gift.infrastructure.ai.chat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatSseCloseTest {

    @Test
    @Timeout(10)
    @DisplayName("close는 60초 idle을 기다리지 않고 열린 응답과 대기 중인 read를 종료한다")
    void close_unblocksReadWithoutWaitingForIdle() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var tasks = Executors.newVirtualThreadPerTaskExecutor();
        var config = new AiChatSseClientConfig();
        var properties = new AiChatProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "service", Duration.ofSeconds(5), Duration.ofSeconds(15));
        var settings = new ChatSseProperties(Duration.ofSeconds(60), Duration.ofSeconds(240), 1);
        var httpClient = config.aiChatSseHttpClient(properties);
        var scheduler = config.chatSseIdleScheduler();
        CountDownLatch finish = new CountDownLatch(1);
        server.setExecutor(tasks);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    finish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try {
            var rest = config.aiChatSseRestClient(properties, settings, httpClient);
            var client = new AiChatSseClient(rest, JsonMapper.builder().build(), settings, scheduler);
            try (var upstream = client.open(UUID.randomUUID(), UUID.randomUUID(), 0)) {
                upstream.body().readNBytes(14);
                CountDownLatch reading = new CountDownLatch(1);
                var reader = tasks.submit(() -> {
                    reading.countDown();
                    try {
                        return upstream.body().read();
                    } catch (IOException exception) {
                        return -1;
                    }
                });
                assertThat(reading.await(1, TimeUnit.SECONDS)).isTrue();
                // EOF도 idle 만료도 없는 응답에서 read가 대기 중임을 확인한다.
                assertThat(reader.isDone()).isFalse();
                tasks.submit(upstream::close).get(2, TimeUnit.SECONDS);
                assertThat(reader.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            }
        } finally {
            finish.countDown();
            server.stop(0);
            scheduler.shutdownNow();
            httpClient.shutdownNow();
            tasks.shutdownNow();
            assertThat(tasks.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
