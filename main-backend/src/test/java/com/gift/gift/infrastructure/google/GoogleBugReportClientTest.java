package com.gift.gift.infrastructure.google;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.messaging.ReportDeliveryResult;
import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleBugReportClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GoogleServiceAccountTokenProvider tokens = mock(GoogleServiceAccountTokenProvider.class);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> writes = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private GoogleBugReportClient google;
    private Consumer<HttpExchange> handler;
    private static final String ID = "00000000-0000-4000-8000-000000000001";

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        handler = this::normalResponse;
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
            if (!"GET".equals(exchange.getRequestMethod())) {
                writes.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            }
            handler.accept(exchange);
        });
        server.start();
        HttpClient redirected = mock(HttpClient.class);
        HttpClient real = HttpClient.newHttpClient();
        when(redirected.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest original = call.getArgument(0);
            URI uri = original.uri();
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())))
                    .timeout(original.timeout().orElseThrow());
            original.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            builder.method(original.method(), original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            return real.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        });
        when(tokens.getAccessToken(anyLong())).thenReturn("test-token");
        google = new GoogleBugReportClient(new BugReportGoogleSheetsProperties("unused", "sheet-id", "제보'탭"),
                tokens, new GoogleApiClient(redirected, mapper), mapper);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JsonNode report() {
        return mapper.createObjectNode().put("reportId", ID).put("description", "=IMPORTXML(\"url\")")
                .put("category", "버그").put("reportedAt", "2026-11-08T00:00:00Z").put("pageUrl", "")
                .put("route", "/").put("viewport", "100x100").put("userAgent", "test").put("email", "").putNull(
                        "userId");
    }

    @Test
    @DisplayName("ID 조회 후 정확한 13열 헤더와 행을 RAW로 쓰고 실제 append 응답으로 성공을 판단한다")
    void writesHeadersAndRowAfterLookup() {
        assertThat(google.deliver(report()).success()).isTrue();
        assertThat(calls).containsExactly("GET", "PUT", "POST");
        assertThat(writes.get(0).path("values").get(0).size()).isEqualTo(13);
        assertThat(writes.get(0).path("values").get(0).get(11).asText()).isEqualTo("사용자 ID");
        assertThat(writes.get(0).path("values").get(0).get(1).asText()).isEqualTo("카테고리");
        var row = writes.get(1).path("values").get(0);
        assertThat(row.size()).isEqualTo(13);
        assertThat(row.get(0).asText()).isEqualTo(ID);
        assertThat(row.get(3).asText()).isEqualTo("=IMPORTXML(\"url\")");
        assertThat(row.get(5).asText()).isEmpty();
    }

    @Test
    @DisplayName("기록된 reportId는 조회 성공으로 완료하고 헤더나 행을 다시 쓰지 않는다")
    void skipsExistingId() {
        handler = e -> respond(e, 200, lookup(true));
        assertThat(google.deliver(report()).errorCode()).isEqualTo("AlreadyRecorded");
        assertThat(calls).containsExactly("GET");
        assertThat(writes).isEmpty();
    }

    @Test
    @DisplayName("조회 실패·잘못된 조회 응답은 미존재가 아니며 어떠한 쓰기도 실행하지 않는다")
    void failedLookupNeverAppends() {
        for (String response : List.of("{}", "{bad",
                "{\"range\":\"A:A\",\"majorDimension\":\"COLUMNS\",\"values\":null}")) {
            handler = e -> respond(e, 200, response);
            var result = google.deliver(report());
            assertThat(result.success()).isFalse();
            assertThat(result.permanent()).isFalse();
        }
        handler = e -> respond(e, 503, "{}");
        assertThat(google.deliver(report()).success()).isFalse();
        assertThat(calls).containsOnly("GET");
        assertThat(writes).isEmpty();
    }

    @Test
    @DisplayName("append 성공 응답 유실은 재시도 가능으로 남기고 다음 ID 조회로 중복 append를 피한다")
    void lostAppendResponseIsRecoveredByLookup() {
        AtomicBoolean recorded = new AtomicBoolean();
        handler = e -> {
            if ("GET".equals(e.getRequestMethod())) respond(e, 200, lookup(recorded.get()));
            else if ("PUT".equals(e.getRequestMethod())) normalResponse(e);
            else {
                recorded.set(true);
                try {
                    e.sendResponseHeaders(200, 100);
                } catch (Exception ignored) {
                }
                e.close();
            }
        };
        assertThat(google.deliver(report()).success()).isFalse();
        assertThat(google.deliver(report()).errorCode()).isEqualTo("AlreadyRecorded");
        assertThat(calls.stream().filter("POST"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("불확실한 append·헤더 성공 응답은 성공으로 처리하지 않는다")
    void requiresWriteConfirmation() {
        handler = e -> {
            if ("POST".equals(e.getRequestMethod())) respond(e, 200, "{\"updates\":{\"updatedRows\":0}}");
            else normalResponse(e);
        };
        assertThat(google.deliver(report()).success()).isFalse();
        calls.clear();
        handler = e -> {
            if ("PUT".equals(e.getRequestMethod())) respond(e, 200, "{}");
            else normalResponse(e);
        };
        assertThat(google.deliver(report()).success()).isFalse();
        assertThat(calls).containsExactly("GET", "PUT");
    }

    @Test
    @DisplayName("429·quota 403은 긴 대기를 보존하고 권한 403·404는 영구 실패로 구분한다")
    void classifiesQuotaAndPermanentFailures() {
        handler = e -> {
            e.getResponseHeaders().set("Retry-After", "65.5");
            respond(e, 429, "{}");
        };
        ReportDeliveryResult rateLimited = google.deliver(report());
        assertThat(rateLimited.retryAfterMs()).isEqualTo(65500);
        assertThat(rateLimited.global()).isTrue();
        assertThat(rateLimited.permanent()).isFalse();
        handler = e -> respond(e, 403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}");
        var userQuota = google.deliver(report());
        assertThat(userQuota.retryAfterMs()).isEqualTo(30000);
        assertThat(userQuota.global()).isFalse();
        assertThat(userQuota.permanent()).isFalse();
        handler = e -> respond(e, 403, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
        assertThat(google.deliver(report()).retryAfterMs()).isEqualTo(30000);
        handler = e -> {
            e.getResponseHeaders().set("Retry-After", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .plusSeconds(70).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME));
            respond(e, 429, "{}");
        };
        assertThat(google.deliver(report()).retryAfterMs()).isBetween(68000L, 71000L);
        for (int status : List.of(400, 401, 403, 404)) {
            handler = e -> respond(e, status, "{}");
            assertThat(google.deliver(report()).permanent()).isTrue();
        }
        handler = e -> respond(e, 503, "{}");
        assertThat(google.deliver(report()).permanent()).isFalse();
    }

    @Test
    @DisplayName("토큰·조회·헤더·append가 같은 전체 20초 예산을 사용한다")
    void boundsEntireSequenceToTwentySeconds() {
        when(tokens.getAccessToken(anyLong())).thenAnswer(call -> {
            Thread.sleep(1000);
            return "test-token";
        });
        handler = e -> {
            try {
                Thread.sleep(7000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            normalResponse(e);
        };
        long start = System.nanoTime();
        assertThat(google.deliver(report()).success()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(19000L, 24000L);
        assertThat(calls).containsExactly("GET", "PUT", "POST");
    }

    private String lookup(boolean existing) {
        return "{\"range\":\"A:A\",\"majorDimension\":\"COLUMNS\",\"values\":[["
                + (existing ? "\"" + ID + "\"" : "") + "]]}";
    }

    private void normalResponse(HttpExchange e) {
        if ("GET".equals(e.getRequestMethod())) respond(e, 200, lookup(false));
        else if ("PUT".equals(e.getRequestMethod())) {
            assertThat(e.getRequestURI().getRawQuery()).contains("valueInputOption=RAW");
            respond(e, 200, "{\"spreadsheetId\":\"sheet-id\",\"updatedRows\":1,\"updatedColumns\":13}");
        } else {
            assertThat(e.getRequestURI().getRawQuery()).contains("valueInputOption=RAW",
                    "insertDataOption=INSERT_ROWS");
            respond(e, 200,
                    "{\"spreadsheetId\":\"sheet-id\",\"updates\":{\"updatedRows\":1,\"updatedColumns\":13,\"updatedCells\":13}}");
        }
    }

    private void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception ignored) {
        } finally {
            exchange.close();
        }
    }
}
