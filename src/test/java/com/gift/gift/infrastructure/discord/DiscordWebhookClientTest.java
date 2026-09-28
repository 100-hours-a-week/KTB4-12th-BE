package com.gift.gift.infrastructure.discord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.bugreport.exception.BugReportException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DiscordWebhookClientTest {

    private static final String WEBHOOK_URL =
            "http://discord.example.test/api/webhooks/1/token";

    private MockRestServiceServer server;
    private DiscordWebhookClient client;

    @BeforeEach
    void setUp() {
        RestClient configured =
                new DiscordWebhookClientConfig().discordWebhookRestClient();
        RestClient.Builder builder = configured.mutate();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DiscordWebhookClient(builder.build());
    }

    @Test
    @DisplayName("정상 응답이면 예외 없이 전송을 완료한다")
    void send_succeeds_onNoContentResponse() {
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThatCode(() -> client.send(
                WEBHOOK_URL,
                "{\"embeds\":[]}",
                screenshot(),
                errorLog()
        )).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("Discord가 5xx를 반환하면 502 예외를 발생시킨다")
    void send_throws_onServerError() {
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.send(
                WEBHOOK_URL,
                "{\"embeds\":[]}",
                null,
                null
        )).isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("429 응답 후 재시도에 성공하면 예외 없이 완료한다")
    void send_retriesOnce_afterRateLimited() {
        server.expect(times(2), requestTo(WEBHOOK_URL))
                .andRespond(sequential());

        assertThatCode(() -> client.send(
                WEBHOOK_URL,
                "{\"embeds\":[]}",
                null,
                null
        )).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("재시도까지 429면 502 예외를 발생시킨다")
    void send_throws_whenRateLimitedTwice() {
        server.expect(times(2), requestTo(WEBHOOK_URL))
                .andRespond(request -> withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0")
                        .createResponse(request));

        assertThatThrownBy(() -> client.send(
                WEBHOOK_URL,
                "{\"embeds\":[]}",
                null,
                null
        )).isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("연결 실패 시 502 예외를 발생시킨다")
    void send_throws_onConnectionFailure() {
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.send(
                WEBHOOK_URL,
                "{\"embeds\":[]}",
                null,
                null
        )).isInstanceOf(BugReportException.class);
    }

    private int callCount = 0;

    private org.springframework.test.web.client.ResponseCreator sequential() {
        return request -> {
            callCount++;
            if (callCount == 1) {
                return withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0")
                        .createResponse(request);
            }
            return withStatus(HttpStatus.NO_CONTENT).createResponse(request);
        };
    }

    private DiscordAttachment screenshot() {
        return new DiscordAttachment(
                "files[0]",
                "screenshot.png",
                MediaType.IMAGE_PNG,
                "png-bytes".getBytes(StandardCharsets.UTF_8)
        );
    }

    private DiscordAttachment errorLog() {
        return new DiscordAttachment(
                "files[1]",
                "errors.txt",
                MediaType.TEXT_PLAIN,
                "error log".getBytes(StandardCharsets.UTF_8)
        );
    }
}
