package com.gift.gift.domain.bugreport.dto;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BugReportSheetEntryTest {

    @Test
    @DisplayName("Discord embed를 Google Sheet 열 순서에 맞게 변환한다")
    void from_mapsDiscordEmbedToSheetEntry() {
        BugReportPayload payload = new BugReportPayload(List.of(
                new BugReportPayload.Embed(
                        "버그 설명",
                        "2026-09-29T00:00:00Z",
                        List.of(
                                new BugReportPayload.Field(
                                        "제보 ID",
                                        "2d01816c-86e7-46a9-a138-51ce3ad21da8"
                                ),
                                new BugReportPayload.Field("카테고리", "제안"),
                                new BugReportPayload.Field(
                                        "페이지 URL",
                                        "https://gift.example/gifts"
                                ),
                                new BugReportPayload.Field("라우트", "/gifts"),
                                new BugReportPayload.Field("뷰포트", "390 x 844"),
                                new BugReportPayload.Field("User-Agent", "test-agent")
                        )
                )
        ));

        BugReportSheetEntry entry = BugReportSheetEntry.from(
                payload,
                "TypeError: failed",
                1L,
                "user@example.com"
        );

        assertThat(entry.toRow())
                .containsExactly(
                        "2d01816c-86e7-46a9-a138-51ce3ad21da8",
                        "1",
                        "user@example.com",
                        "제안",
                        "2026-09-29T00:00:00Z",
                        "버그 설명",
                        "https://gift.example/gifts\n/gifts",
                        "TypeError: failed",
                        "390 x 844",
                        "test-agent",
                        "신규",
                        "",
                        ""
                );
    }
}
