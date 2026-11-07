package com.gift.gift.domain.bugreport.dto;

import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateBugReportRequest(
    @NotBlank(message = "REQUIRED")
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        message = "INVALID_FORMAT") String reportId,
    @NotBlank(message = "REQUIRED") String description,
    String category,
    String pageUrl,
    String route,
    String viewport,
    String userAgent,
    Instant reportedAt
) {
}
