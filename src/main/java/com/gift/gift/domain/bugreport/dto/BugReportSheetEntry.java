package com.gift.gift.domain.bugreport.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BugReportSheetEntry(
        String reportId,
        String userId,
        String email,
        String category,
        String reportedAt,
        String message,
        String pageAndRoute,
        String errorsText,
        String viewport,
        String userAgent
) {

    private static final String DEFAULT_CATEGORY = "버그";

    public static BugReportSheetEntry from(
            BugReportPayload payload,
            String errorsText,
            Long userId,
            String email
    ) {
        BugReportPayload.Embed embed = payload.embeds().getFirst();

        return new BugReportSheetEntry(
                normalizeReportId(embed.fieldValue("제보 ID")),
                userId == null ? "익명" : userId.toString(),
                valueOrEmpty(email),
                valueOrDefault(
                        embed.fieldValue("카테고리"),
                        DEFAULT_CATEGORY
                ),
                valueOrDefault(embed.timestamp(), Instant.now().toString()),
                valueOrEmpty(embed.description()),
                joinPageAndRoute(
                        embed.fieldValue("페이지 URL"),
                        embed.fieldValue("라우트")
                ),
                valueOrEmpty(errorsText),
                embed.fieldValue("뷰포트"),
                embed.fieldValue("User-Agent")
        );
    }

    public List<String> toRow() {
        return List.of(
                reportId,
                category,
                reportedAt,
                message,
                pageAndRoute,
                errorsText,
                viewport,
                userAgent,
                "신규",
                "",
                "",
                userId,
                email
        );
    }

    private static String normalizeReportId(String reportId) {
        try {
            return UUID.fromString(reportId).toString();
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID().toString();
        }
    }

    private static String joinPageAndRoute(String pageUrl, String route) {
        String normalizedPageUrl = valueOrEmpty(pageUrl);
        String normalizedRoute = valueOrEmpty(route);

        if (normalizedPageUrl.isBlank()) {
            return normalizedRoute;
        }
        if (normalizedRoute.isBlank()) {
            return normalizedPageUrl;
        }
        return normalizedPageUrl + "\n" + normalizedRoute;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
