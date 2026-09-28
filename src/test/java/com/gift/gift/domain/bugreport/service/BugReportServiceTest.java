package com.gift.gift.domain.bugreport.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.support.BugReportPayloadValidator;
import com.gift.gift.domain.bugreport.support.BugReportRateLimiter;
import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.infrastructure.discord.DiscordAttachment;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BugReportServiceTest {

    private static final Long USER_ID = 1L;
    private static final String CLIENT_IP = "127.0.0.1";
    private static final String WEBHOOK_URL =
            "https://discord.example.test/api/webhooks/1/token";
    private static final String VALID_PAYLOAD =
            "{\"embeds\":[{\"description\":\"버그 설명\"}]}";

    private DiscordWebhookClient discordWebhookClient;
    private BugReportService service;

    @BeforeEach
    void setUp() {
        discordWebhookClient = mock(DiscordWebhookClient.class);
        service = new BugReportService(
                new BugReportRateLimiter(
                        Clock.fixed(Instant.now(), ZoneId.of("UTC"))
                ),
                new BugReportPayloadValidator(JsonMapper.builder().build()),
                new BugReportWebhookProperties(WEBHOOK_URL),
                discordWebhookClient
        );
    }

    @Test
    @DisplayName("스크린샷과 로그가 모두 있으면 Discord로 그대로 전달한다")
    void submitBugReport_sendsToDiscord_withAllAttachments() {
        MockMultipartFile screenshot = new MockMultipartFile(
                "files[0]", "screenshot.png", "image/png", "png-bytes".getBytes()
        );
        MockMultipartFile errorLog = new MockMultipartFile(
                "files[1]", "errors.txt", "text/plain", "log".getBytes()
        );

        service.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, screenshot, errorLog
        );

        org.mockito.ArgumentCaptor<DiscordAttachment> screenshotCaptor =
                org.mockito.ArgumentCaptor.forClass(DiscordAttachment.class);
        org.mockito.ArgumentCaptor<DiscordAttachment> errorLogCaptor =
                org.mockito.ArgumentCaptor.forClass(DiscordAttachment.class);

        verify(discordWebhookClient).send(
                eq(WEBHOOK_URL),
                eq(VALID_PAYLOAD),
                screenshotCaptor.capture(),
                errorLogCaptor.capture()
        );

        DiscordAttachment capturedScreenshot = screenshotCaptor.getValue();
        assertThat(capturedScreenshot.partName()).isEqualTo("files[0]");
        assertThat(capturedScreenshot.filename()).isEqualTo("screenshot.png");
        assertThat(capturedScreenshot.contentType())
                .isEqualTo(org.springframework.http.MediaType.IMAGE_PNG);
        assertThat(capturedScreenshot.content())
                .isEqualTo("png-bytes".getBytes());

        DiscordAttachment capturedErrorLog = errorLogCaptor.getValue();
        assertThat(capturedErrorLog.partName()).isEqualTo("files[1]");
        assertThat(capturedErrorLog.filename()).isEqualTo("errors.txt");
        assertThat(capturedErrorLog.contentType())
                .isEqualTo(org.springframework.http.MediaType.TEXT_PLAIN);
        assertThat(capturedErrorLog.content()).isEqualTo("log".getBytes());
    }

    @Test
    @DisplayName("사용자 ID가 없어도(비로그인) 예외 없이 Discord에 전달한다")
    void submitBugReport_sendsToDiscord_whenUserIdNull() {
        assertThatCode(() -> service.submitBugReport(
                null, CLIENT_IP, VALID_PAYLOAD, null, null
        )).doesNotThrowAnyException();

        verify(discordWebhookClient).send(
                eq(WEBHOOK_URL),
                eq(VALID_PAYLOAD),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    @DisplayName("사용자 ID가 없으면 사용자별 rate limit은 적용하지 않고 IP 기준만 적용한다")
    void submitBugReport_skipsUserRateLimit_whenUserIdNull() {
        for (int i = 0; i < 6; i++) {
            service.submitBugReport(
                    null, CLIENT_IP, VALID_PAYLOAD, null, null
            );
        }

        verify(discordWebhookClient, Mockito.times(6)).send(
                eq(WEBHOOK_URL),
                eq(VALID_PAYLOAD),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    @DisplayName("파일이 없어도 payload만으로 Discord에 전달한다")
    void submitBugReport_sendsToDiscord_withoutFiles() {
        assertThatCode(() -> service.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, null, null
        )).doesNotThrowAnyException();

        verify(discordWebhookClient).send(
                eq(WEBHOOK_URL),
                eq(VALID_PAYLOAD),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    @DisplayName("description이 빈 payload는 Discord에 전달하지 않고 예외를 던진다")
    void submitBugReport_throws_whenDescriptionBlank() {
        String invalidPayload = "{\"embeds\":[{\"description\":\"\"}]}";

        assertThatThrownBy(() -> service.submitBugReport(
                USER_ID, CLIENT_IP, invalidPayload, null, null
        )).isInstanceOf(BugReportException.class);

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    @DisplayName("허용되지 않은 스크린샷 형식이면 예외를 던진다")
    void submitBugReport_throws_whenScreenshotTypeNotAllowed() {
        MockMultipartFile screenshot = new MockMultipartFile(
                "files[0]", "screenshot.gif", "image/gif", "gif-bytes".getBytes()
        );

        assertThatThrownBy(() -> service.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, screenshot, null
        )).isInstanceOf(BugReportException.class);

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    @DisplayName("스크린샷이 8MB를 초과하면 예외를 던진다")
    void submitBugReport_throws_whenScreenshotTooLarge() {
        byte[] tooLarge = new byte[8 * 1024 * 1024 + 1];
        MockMultipartFile screenshot = new MockMultipartFile(
                "files[0]", "screenshot.png", "image/png", tooLarge
        );

        assertThatThrownBy(() -> service.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, screenshot, null
        )).isInstanceOf(BugReportException.class);

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    @DisplayName("사용자당 분당 5회를 초과하면 예외를 던지고 이후 요청은 Discord로 전달하지 않는다")
    void submitBugReport_throws_whenUserRateLimitExceeded() {
        for (int i = 0; i < 5; i++) {
            service.submitBugReport(
                    USER_ID, CLIENT_IP, VALID_PAYLOAD, null, null
            );
        }

        Mockito.clearInvocations(discordWebhookClient);

        assertThatThrownBy(() -> service.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, null, null
        )).isInstanceOf(BugReportException.class);

        verify(discordWebhookClient, never()).send(
                any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("웹훅 URL이 설정되지 않으면 예외를 던진다")
    void submitBugReport_throws_whenWebhookNotConfigured() {
        BugReportService unconfiguredService = new BugReportService(
                new BugReportRateLimiter(
                        Clock.fixed(Instant.now(), ZoneId.of("UTC"))
                ),
                new BugReportPayloadValidator(JsonMapper.builder().build()),
                new BugReportWebhookProperties(null),
                discordWebhookClient
        );

        assertThatThrownBy(() -> unconfiguredService.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, null, null
        )).isInstanceOf(BugReportException.class);

        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    @DisplayName("Discord 클라이언트가 실패하면 예외가 그대로 전파된다")
    void submitBugReport_propagatesException_whenDiscordClientFails() {
        Mockito.doThrow(new BugReportException(
                        com.gift.gift.domain.bugreport.exception.BugReportErrorCode.DELIVERY_FAILED
                ))
                .when(discordWebhookClient)
                .send(any(), any(), any(), any());

        assertThatThrownBy(() -> service.submitBugReport(
                USER_ID, CLIENT_IP, VALID_PAYLOAD, null, null
        )).isInstanceOf(BugReportException.class);
    }
}
