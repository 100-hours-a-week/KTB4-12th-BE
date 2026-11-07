package com.gift.gift.domain.bugreport.dto;

public record BugReportAcceptedResponse(String reportId) {
    public static BugReportAcceptedResponse from(String reportId) {
        return new BugReportAcceptedResponse(reportId);
    }
}
