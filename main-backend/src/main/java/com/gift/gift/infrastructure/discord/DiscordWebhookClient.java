package com.gift.gift.infrastructure.discord;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;

@Component
@RequiredArgsConstructor
public class DiscordWebhookClient {

    private static final long MAX_RETRY_WAIT_SECONDS = 5;

    private final RestClient discordWebhookRestClient;

    public void send(
            String webhookUrl,
            String payloadJson,
            DiscordAttachment screenshot,
            DiscordAttachment errorLog
    ) {
        MultiValueMap<String, HttpEntity<?>> body =
                buildBody(payloadJson, screenshot, errorLog);

        try {
            attempt(webhookUrl, body);
        } catch (DiscordRateLimitedException exception) {
            sleep(Math.min(
                    exception.retryAfterSeconds(),
                    MAX_RETRY_WAIT_SECONDS
            ));

            try {
                attempt(webhookUrl, body);
            } catch (DiscordRateLimitedException retryException) {
                throw deliveryFailed(429);
            }
        }
    }

    private void attempt(
            String webhookUrl,
            MultiValueMap<String, HttpEntity<?>> body
    ) {
        try {
            discordWebhookRestClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(
                            status -> status.value() == 429,
                            (request, response) -> {
                                throw new DiscordRateLimitedException(
                                        parseRetryAfterSeconds(
                                                response.getHeaders()
                                                        .getFirst("Retry-After")
                                        )
                                );
                            }
                    )
                    .onStatus(
                            HttpStatusCode::isError,
                            (request, response) -> {
                                throw deliveryFailed(
                                        response.getStatusCode().value()
                                );
                            }
                    )
                    .toBodilessEntity();
        } catch (ResourceAccessException exception) {
            throw deliveryFailed(exception);
        } catch (RestClientException exception) {
            throw deliveryFailed(exception);
        }
    }

    private MultiValueMap<String, HttpEntity<?>> buildBody(
            String payloadJson,
            DiscordAttachment screenshot,
            DiscordAttachment errorLog
    ) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        builder.part("payload_json", payloadJson, MediaType.APPLICATION_JSON);

        if (screenshot != null) {
            addFilePart(builder, screenshot);
        }

        if (errorLog != null) {
            addFilePart(builder, errorLog);
        }

        return builder.build();
    }

    private void addFilePart(
            MultipartBodyBuilder builder,
            DiscordAttachment attachment
    ) {
        ByteArrayResource resource = new ByteArrayResource(
                attachment.content()
        ) {
            @Override
            public String getFilename() {
                return attachment.filename();
            }
        };

        builder.part(attachment.partName(), resource, attachment.contentType());
    }

    private long parseRetryAfterSeconds(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return MAX_RETRY_WAIT_SECONDS;
        }

        try {
            return Math.round(Double.parseDouble(headerValue));
        } catch (NumberFormatException exception) {
            return MAX_RETRY_WAIT_SECONDS;
        }
    }

    private void sleep(long seconds) {
        if (seconds <= 0) {
            return;
        }

        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private BugReportException deliveryFailed(int statusCode) {
        return new BugReportException(
                BugReportErrorCode.DELIVERY_FAILED,
                "Discord 전송 실패 (HTTP " + statusCode + ")"
        );
    }

    private BugReportException deliveryFailed(Exception cause) {
        return new BugReportException(
                BugReportErrorCode.DELIVERY_FAILED,
                "Discord 전송 실패 (" + cause.getClass().getSimpleName() + ")"
        );
    }
}
