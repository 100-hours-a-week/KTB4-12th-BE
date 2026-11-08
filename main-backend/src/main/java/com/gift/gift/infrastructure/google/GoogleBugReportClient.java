package com.gift.gift.infrastructure.google;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.dto.BugReportSheetEntry;
import com.gift.gift.domain.bugreport.messaging.ReportDeliveryResult;
import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;

@Component
public class GoogleBugReportClient {
    private static final String API = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final List<String> HEADERS = List.of("제보 ID", "카테고리", "발생 시각", "제보 내용",
        "페이지·라우트", "수집된 오류", "화면 크기", "User-Agent", "처리 상태", "담당자", "처리 의견", "사용자 ID", "이메일");
    private final BugReportGoogleSheetsProperties properties;
    private final GoogleServiceAccountTokenProvider tokens;
    private final GoogleApiClient client;
    private final ObjectMapper mapper;

    public GoogleBugReportClient(BugReportGoogleSheetsProperties properties, GoogleServiceAccountTokenProvider tokens,
                                 GoogleApiClient client, ObjectMapper mapper) {
        this.properties = properties;
        this.tokens = tokens;
        this.client = client;
        this.mapper = mapper;
    }

    public ReportDeliveryResult deliver(JsonNode report) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            String token = tokens.getAccessToken(deadline);
            var found = client.request(request("A:A", "?majorDimension=COLUMNS", token).GET(), deadline);
            if (!found.path("range").isString() || found.path("range").asText().isBlank()
                || !"COLUMNS".equals(found.path("majorDimension").asText())
                || found.has("values") && !found.path("values").isArray()) {
                throw new GoogleApiFailure(false, 0, false, "InvalidLookupResponse");
            }
            String id = report.path("reportId").asText();
            for (JsonNode column : found.path("values")) {
                if (!column.isArray()) throw new GoogleApiFailure(false, 0, false, "InvalidLookupResponse");
                for (JsonNode value : column) {
                    if (!value.isString()) throw new GoogleApiFailure(false, 0, false, "InvalidLookupResponse");
                    if (id.equals(value.asText())) return success("AlreadyRecorded");
                }
            }
            var headers = client.request(request("A1:M1", "?valueInputOption=RAW", token)
                .PUT(json(HEADERS)), deadline);
            if (!properties.spreadsheetId().equals(headers.path("spreadsheetId").asText())
                || headers.path("updatedRows").asInt() != 1 || headers.path("updatedColumns").asInt() != 13) {
                throw new GoogleApiFailure(false, 0, false, "InvalidHeaderResponse");
            }
            var appended = client.request(request("A:M", ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", token)
                .POST(json(BugReportSheetEntry.from(report).toRow())), deadline);
            if (!properties.spreadsheetId().equals(appended.path("spreadsheetId").asText())
                || appended.path("updates").path("updatedRows").asInt() != 1
                || appended.path("updates").path("updatedColumns").asInt() != 13
                || appended.path("updates").path("updatedCells").asInt() != 13) {
                throw new GoogleApiFailure(false, 0, false, "InvalidAppendResponse");
            }
            return success("Appended");
        } catch (GoogleApiFailure failure) {
            return failure.result();
        }
    }

    private ReportDeliveryResult success(String code) {
        return new ReportDeliveryResult(true, false, 0, false, code);
    }

    private HttpRequest.BodyPublisher json(List<String> row) {
        return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("values", List.of(row))));
    }

    private HttpRequest.Builder request(String columns, String suffix, String token) {
        String range = "'" + properties.sheetName().replace("'", "''") + "'!" + columns;
        return HttpRequest.newBuilder(URI.create(API + encode(properties.spreadsheetId()) + "/values/"
            + encode(range) + suffix)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
