package com.gift.gift.domain.bugreport.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BugReportSheetEntryTest {
    @Test
    @DisplayName("평평한 리포트 JSON은 ID를 재생성하지 않고 기존 13열로 기록하며 오류 로그는 비운다")
    void mapsFlatReportAndPreservesId() {
        var report = new ObjectMapper().createObjectNode()
            .put("reportId", "2d01816c-86e7-46a9-a138-51ce3ad21da8").put("category", "제안")
            .put("reportedAt", "2026-09-29T00:00:00Z").put("description", "버그 설명")
            .put("pageUrl", "https://gift.example/gifts").put("route", "/gifts")
            .put("viewport", "390 x 844").put("userAgent", "test-agent")
            .put("userId", 1).put("email", "user@example.com");
        assertThat(BugReportSheetEntry.from(report).toRow()).containsExactly(
            "2d01816c-86e7-46a9-a138-51ce3ad21da8", "제안", "2026-09-29T00:00:00Z", "버그 설명",
            "https://gift.example/gifts\n/gifts", "", "390 x 844", "test-agent", "신규", "", "", "1", "user@example.com");
        report.putNull("userId").put("email", "").put("pageUrl", "");
        assertThat(BugReportSheetEntry.from(report).toRow()).hasSize(13).containsSubsequence("", "test-agent");
        assertThat(BugReportSheetEntry.from(report).userId()).isEmpty();
        assertThat(BugReportSheetEntry.from(report).pageAndRoute()).isEqualTo("/gifts");
    }
}
