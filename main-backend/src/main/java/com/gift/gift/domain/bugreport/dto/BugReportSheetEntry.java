package com.gift.gift.domain.bugreport.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record BugReportSheetEntry(String reportId, String userId, String email, String category,
                                  String reportedAt, String message, String pageAndRoute,
                                  String viewport, String userAgent) {
    public static BugReportSheetEntry from(JsonNode report) {
        String page = report.path("pageUrl").asText();
        String route = report.path("route").asText();
        String location = page.isBlank() ? route : route.isBlank() ? page : page + "\n" + route;
        return new BugReportSheetEntry(report.path("reportId").asText(),
                report.path("userId").isNull() ? "" : report.path("userId").asText(), report.path("email").asText(),
                report.path("category").asText(), report.path("reportedAt").asText(),
                report.path("description").asText(), location, report.path("viewport").asText(),
                report.path("userAgent").asText());
    }

    public List<String> toRow() {
        return List.of(reportId, category, reportedAt, message, pageAndRoute, "", viewport, userAgent,
                "신규", "", "", userId, email);
    }
}
