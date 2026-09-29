package com.gift.gift.infrastructure.google;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gift.gift.domain.bugreport.dto.BugReportSheetEntry;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;

@Component
public class GoogleBugReportClient {

    private static final String SHEETS_API_BASE =
            "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final List<String> SHEET_HEADERS = List.of(
            "제보 ID",
            "사용자 ID",
            "이메일",
            "카테고리",
            "발생 시각",
            "제보 내용",
            "페이지·라우트",
            "수집된 오류",
            "화면 크기",
            "User-Agent",
            "처리 상태",
            "담당자",
            "처리 의견"
    );

    private final BugReportGoogleSheetsProperties properties;
    private final GoogleServiceAccountTokenProvider tokenProvider;
    private final RestClient restClient;

    public GoogleBugReportClient(
            BugReportGoogleSheetsProperties properties,
            GoogleServiceAccountTokenProvider tokenProvider,
            @Qualifier("googleApiRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
    }

    public boolean appendIfAbsent(BugReportSheetEntry entry) {
        String accessToken = tokenProvider.getAccessToken();

        try {
            if (containsReportId(accessToken, entry.reportId())) {
                return false;
            }

            appendRow(accessToken, entry.toRow());
            return true;
        } catch (RestClientException exception) {
            throw new BugReportException(
                    BugReportErrorCode.DELIVERY_FAILED,
                    "Google Sheets 기록에 실패했습니다."
            );
        }
    }

    private boolean containsReportId(String accessToken, String reportId) {
        SheetValuesResponse response = restClient.get()
                .uri(sheetValuesUri("A:A"))
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(SheetValuesResponse.class);

        List<String> firstColumn = response == null || response.values() == null
                ? List.of()
                : response.values().stream()
                .flatMap(List::stream)
                .toList();
        if (firstColumn.isEmpty()) {
            writeHeaders(accessToken);
            return false;
        }

        return firstColumn.stream().anyMatch(reportId::equals);
    }

    private void writeHeaders(String accessToken) {
        URI uri = URI.create(
                SHEETS_API_BASE
                        + encode(properties.spreadsheetId())
                        + "/values/"
                        + encode(quotedRange("A1:M1"))
                        + "?valueInputOption=RAW"
        );

        restClient.put()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("values", List.of(SHEET_HEADERS)))
                .retrieve()
                .toBodilessEntity();
    }

    private void appendRow(String accessToken, List<String> row) {
        URI uri = URI.create(
                SHEETS_API_BASE
                        + encode(properties.spreadsheetId())
                        + "/values/"
                        + encode(quotedRange("A:M"))
                        + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS"
        );

        restClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("values", List.of(row)))
                .retrieve()
                .toBodilessEntity();
    }

    private URI sheetValuesUri(String columns) {
        return URI.create(
                SHEETS_API_BASE
                        + encode(properties.spreadsheetId())
                        + "/values/"
                        + encode(quotedRange(columns))
                        + "?majorDimension=COLUMNS"
        );
    }

    private String quotedRange(String columns) {
        String escapedSheetName = properties.sheetName().replace("'", "''");
        return "'" + escapedSheetName + "'!" + columns;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SheetValuesResponse(List<List<String>> values) {
    }
}
