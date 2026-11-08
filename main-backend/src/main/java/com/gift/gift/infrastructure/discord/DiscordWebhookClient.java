package com.gift.gift.infrastructure.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.messaging.ReportDeliveryResult;

@Component
public class DiscordWebhookClient {
    private final HttpClient client;
    private final ObjectMapper mapper;

    public DiscordWebhookClient(@Qualifier("discordWebhookHttpClient") HttpClient discordWebhookHttpClient,
                                ObjectMapper mapper) {
        this.client = discordWebhookHttpClient;
        this.mapper = mapper;
    }

    public ReportDeliveryResult send(String webhookUrl, String payloadJson) {
        URI uri = UriComponentsBuilder.fromUriString(webhookUrl).replaceQueryParam("wait", "true")
            .build(true).toUri();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson)).build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // The future includes body consumption; a slow response body cannot extend the attempt indefinitely.
            var response = pending.get(20, TimeUnit.SECONDS);
            return classify(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null),
                response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ReportDeliveryResult(false, false, 0, false, "Interrupted");
        } catch (Exception exception) {
            return new ReportDeliveryResult(false, false, 0, false, "NetworkOrTimeout");
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    ReportDeliveryResult classify(int status, String retryAfter, String body) {
        JsonNode response = null;
        try {
            if (body != null && body.length() <= 65536) response = mapper.readTree(body);
        } catch (RuntimeException ignored) {
            // An invalid success response is uncertain delivery, so it must remain retryable.
        }
        if (status >= 200 && status < 300 && response != null
            && response.path("id").asText("").matches("[0-9]+")) {
            return new ReportDeliveryResult(true, false, 0, false, "Success");
        }
        if (status == 429) {
            long delay = Math.max(parseSeconds(retryAfter),
                response == null ? 0 : parseSeconds(response.path("retry_after").asText("")));
            return new ReportDeliveryResult(false, false, Math.max(30000, delay),
                response != null && response.path("global").asBoolean(false), "RateLimited");
        }
        boolean permanent = status == 400 || status == 401 || status == 403 || status == 404 || status == 405;
        return new ReportDeliveryResult(false, permanent, 0, false,
            permanent ? "InvalidRequestOrCredentials" : "HttpOrInvalidResponse");
    }

    private long parseSeconds(String seconds) {
        try {
            double millis = Double.parseDouble(seconds) * 1000;
            return Double.isFinite(millis) && millis > 0 && millis < Long.MAX_VALUE / 2.0
                ? (long) Math.ceil(millis) : 0;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

}
