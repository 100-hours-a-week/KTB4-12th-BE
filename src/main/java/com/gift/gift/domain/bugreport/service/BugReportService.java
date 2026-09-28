package com.gift.gift.domain.bugreport.service;

import java.io.IOException;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.support.BugReportPayloadValidator;
import com.gift.gift.domain.bugreport.support.BugReportRateLimiter;
import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.infrastructure.discord.DiscordAttachment;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class BugReportService {

    private static final Set<String> ALLOWED_SCREENSHOT_TYPES = Set.of(
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE
    );
    private static final long MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024;

    private final BugReportRateLimiter rateLimiter;
    private final BugReportPayloadValidator payloadValidator;
    private final BugReportWebhookProperties webhookProperties;
    private final DiscordWebhookClient discordWebhookClient;

    public void submitBugReport(
            Long userId,
            String clientIp,
            String payloadJson,
            MultipartFile screenshot,
            MultipartFile errorLog
    ) {
        if (userId != null) {
            rateLimiter.checkUser(userId);
        }

        rateLimiter.checkIp(clientIp);

        payloadValidator.validate(payloadJson);
        validateScreenshot(screenshot);

        if (!webhookProperties.isConfigured()) {
            log.warn(
                    "Discord Webhook이 설정되지 않아 버그 제보를 전송할 수 없습니다. userId={}",
                    userId
            );

            throw new BugReportException(
                    BugReportErrorCode.WEBHOOK_NOT_CONFIGURED
            );
        }

        discordWebhookClient.send(
                webhookProperties.webhookUrl(),
                payloadJson,
                toAttachment(screenshot, "files[0]"),
                toAttachment(errorLog, "files[1]")
        );

        log.info(
                "버그 제보를 전송했습니다. userId={}, hasScreenshot={}, "
                        + "screenshotBytes={}, errorLogBytes={}",
                userId,
                hasContent(screenshot),
                size(screenshot),
                size(errorLog)
        );
    }

    private void validateScreenshot(MultipartFile screenshot) {
        if (!hasContent(screenshot)) {
            return;
        }

        String contentType = screenshot.getContentType();

        if (contentType == null
                || !ALLOWED_SCREENSHOT_TYPES.contains(contentType)) {
            throw new BugReportException(
                    BugReportErrorCode.SCREENSHOT_INVALID_TYPE
            );
        }

        if (screenshot.getSize() > MAX_SCREENSHOT_BYTES) {
            throw new BugReportException(
                    BugReportErrorCode.SCREENSHOT_TOO_LARGE
            );
        }
    }

    private DiscordAttachment toAttachment(
            MultipartFile file,
            String partName
    ) {
        if (!hasContent(file)) {
            return null;
        }

        try {
            String contentType = file.getContentType() != null
                    ? file.getContentType()
                    : MediaType.APPLICATION_OCTET_STREAM_VALUE;

            return new DiscordAttachment(
                    partName,
                    file.getOriginalFilename(),
                    MediaType.parseMediaType(contentType),
                    file.getBytes()
            );
        } catch (IOException exception) {
            throw new BugReportException(
                    BugReportErrorCode.ATTACHMENT_READ_FAILED
            );
        }
    }

    private boolean hasContent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private long size(MultipartFile file) {
        return hasContent(file) ? file.getSize() : 0;
    }
}
