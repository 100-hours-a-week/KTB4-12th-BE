package com.gift.gift.infrastructure.google;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.bugreport.dto.BugReportSheetEntry;
import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleBugReportClientTest {

    private static final String REPORT_ID =
            "2d01816c-86e7-46a9-a138-51ce3ad21da8";

    private MockRestServiceServer server;
    private GoogleBugReportClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GoogleServiceAccountTokenProvider tokenProvider =
                mock(GoogleServiceAccountTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("access-token");
        client = new GoogleBugReportClient(
                new BugReportGoogleSheetsProperties(
                        "credentials",
                        "spreadsheet-id",
                        "버그 제보"
                ),
                tokenProvider,
                builder.build()
        );
    }

    @Test
    @DisplayName("새 제보이면 Sheet에 행을 추가한다")
    void appendIfAbsent_appendsRow_forNewReport() {
        server.expect(once(), requestTo(containsString("/values/")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"values\":[[\"제보 ID\"]]}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(once(), requestTo(containsString("valueInputOption=RAW")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString("사용자 ID")))
                .andExpect(content().string(containsString("이메일")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString(":append")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        boolean appended = client.appendIfAbsent(entry());

        assertThat(appended).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("같은 제보 ID가 이미 있으면 Sheet 추가를 생략한다")
    void appendIfAbsent_skipsWrite_forDuplicateReport() {
        server.expect(once(), requestTo(containsString("/values/")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"values\":[[\"제보 ID\",\"" + REPORT_ID + "\"]]}",
                        MediaType.APPLICATION_JSON
                ));

        boolean appended = client.appendIfAbsent(entry());

        assertThat(appended).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("빈 시트이면 열 헤더를 만든 뒤 제보 행을 추가한다")
    void appendIfAbsent_writesHeaders_whenSheetIsEmpty() {
        server.expect(once(), requestTo(containsString("/values/")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("valueInputOption=RAW")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString(":append")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        boolean appended = client.appendIfAbsent(entry());

        assertThat(appended).isTrue();
        server.verify();
    }

    private BugReportSheetEntry entry() {
        return new BugReportSheetEntry(
                REPORT_ID,
                "1",
                "user@example.com",
                "버그",
                "2026-09-29T00:00:00Z",
                "버그 설명",
                "https://gift.example/gifts\n/gifts",
                "TypeError: failed",
                "390 x 844",
                "test-agent"
        );
    }
}
