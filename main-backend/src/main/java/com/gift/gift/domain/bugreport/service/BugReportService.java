package com.gift.gift.domain.bugreport.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.gift.gift.domain.bugreport.dto.BugReportPayload;
import com.gift.gift.domain.bugreport.dto.BugReportSheetEntry;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;
import com.gift.gift.domain.bugreport.support.BugReportPayloadValidator;
import com.gift.gift.domain.bugreport.support.BugReportRateLimiter;
import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.infrastructure.discord.DiscordAttachment;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;
import com.gift.gift.infrastructure.google.GoogleBugReportClient;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;

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
    private final BugReportGoogleSheetsProperties googleSheetsProperties;
    private final UserRepository userRepository;
    private final DiscordWebhookClient discordWebhookClient;
    private final GoogleBugReportClient googleBugReportClient;

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

        BugReportPayload payload = payloadValidator.validate(payloadJson);
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
        if (!googleSheetsProperties.isConfigured()) {
            log.warn(
                    "Google Sheets가 설정되지 않아 버그 제보를 저장할 수 없습니다. userId={}",
                    userId
            );

            throw new BugReportException(
                    BugReportErrorCode.GOOGLE_SHEETS_NOT_CONFIGURED
            );
        }

        String email = resolveEmail(userId);
        String discordPayloadJson = payloadValidator.addReporter(
                payloadJson,
                userId,
                email
        );
        BugReportSheetEntry sheetEntry = BugReportSheetEntry.from(
                payload,
                readAsUtf8(errorLog),
                userId,
                email
        );
        boolean appended = googleBugReportClient.appendIfAbsent(sheetEntry);

        discordWebhookClient.send(
                webhookProperties.webhookUrl(),
                discordPayloadJson,
                toAttachment(screenshot, "files[0]"),
                toAttachment(errorLog, "files[1]")
        );

        log.info(
                "버그 제보를 전송했습니다. userId={}, hasScreenshot={}, "
                        + "screenshotBytes={}, errorLogBytes={}, sheetAppended={}",
                userId,
                hasContent(screenshot),
                size(screenshot),
                size(errorLog),
                appended
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

    private String resolveEmail(Long userId) {
        if (userId == null) {
            return "";
        }

        return userRepository.findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                )
                .map(User::getEmail)
                .orElse("");
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

    private String readAsUtf8(MultipartFile file) {
        if (!hasContent(file)) {
            return "수집된 에러가 없습니다.";
        }

        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
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
