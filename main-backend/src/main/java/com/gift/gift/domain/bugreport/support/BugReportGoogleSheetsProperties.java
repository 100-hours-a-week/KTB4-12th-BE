package com.gift.gift.domain.bugreport.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bug-report.google-sheets")
public record BugReportGoogleSheetsProperties(
        String credentialsJsonBase64,
        String spreadsheetId,
        String sheetName
) {

    public boolean isConfigured() {
        return hasText(credentialsJsonBase64)
                && hasText(spreadsheetId)
                && hasText(sheetName);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
