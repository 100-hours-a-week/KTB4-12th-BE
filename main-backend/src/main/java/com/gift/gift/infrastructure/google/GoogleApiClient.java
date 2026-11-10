package com.gift.gift.infrastructure.google;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GoogleApiClient {
    private final HttpClient client;
    private final ObjectMapper mapper;

    public GoogleApiClient(@Qualifier("googleApiHttpClient") HttpClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    JsonNode request(HttpRequest.Builder request, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new GoogleApiFailure(false, 0, false, "Timeout");
        var pending = client.sendAsync(request.timeout(Duration.ofNanos(remaining)).build(),
            HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GoogleApiFailure(false, 0, false, "Interrupted");
        } catch (Exception exception) {
            throw new GoogleApiFailure(false, 0, false, "NetworkOrTimeout");
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
        JsonNode body = null;
        try {
            body = mapper.readTree(response.body());
        } catch (RuntimeException ignored) {
            // Invalid responses are neither an empty sheet nor a successful write.
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            boolean userQuota = hasReason(body, "userRateLimitExceeded");
            boolean quota = status == 429 || status == 403 && (userQuota
                || hasReason(body, "rateLimitExceeded") || hasReason(body, "quotaExceeded")
                || body != null && "RESOURCE_EXHAUSTED".equals(body.path("error").path("status").asText()));
            if (quota) {
                throw new GoogleApiFailure(false, Math.max(30000,
                    retryAfterMs(response.headers().firstValue("Retry-After").orElse(null))),
                    !userQuota, "QuotaExceeded");
            }
            boolean permanent = status == 400 || status == 401 || status == 403 || status == 404 || status == 405;
            throw new GoogleApiFailure(permanent, 0, false,
                permanent ? "InvalidRequestOrCredentials" : "HttpFailure");
        }
        if (body == null || !body.isObject()) {
            throw new GoogleApiFailure(false, 0, false, "InvalidResponse");
        }
        return body;
    }

    private boolean hasReason(JsonNode body, String reason) {
        if (body == null) return false;
        for (JsonNode error : body.path("error").path("errors")) {
            if (reason.equals(error.path("reason").asText())) return true;
        }
        return false;
    }

    private long retryAfterMs(String value) {
        if (value == null) return 0;
        try {
            double ms = Double.parseDouble(value) * 1000;
            return Double.isFinite(ms) && ms > 0 && ms < Long.MAX_VALUE / 2.0 ? (long) Math.ceil(ms) : 0;
        } catch (NumberFormatException exception) {
            try {
                return Math.max(0, Duration.between(Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
            } catch (RuntimeException invalidDate) {
                return 0;
            }
        }
    }
}
