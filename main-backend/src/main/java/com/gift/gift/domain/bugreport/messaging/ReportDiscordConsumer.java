package com.gift.gift.domain.bugreport.messaging;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.MessageEnvelope;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;

public class ReportDiscordConsumer implements ChannelAwareMessageListener {
    public static final String RETRY_COUNT = "delivery.retryCount";
    public static final String NEXT_ATTEMPT_AT = "delivery.nextAttemptAt";
    private final ObjectMapper mapper;
    private final DiscordWebhookClient discord;
    private final String webhookUrl;
    private final ReportDiscordCooldown cooldown;
    private final RabbitPublisher publisher;
    private final MeterRegistry meters;
    private final Runnable recover;
    private final Runnable recovered;

    public ReportDiscordConsumer(ObjectMapper mapper, DiscordWebhookClient discord, String webhookUrl,
                                 ReportDiscordCooldown cooldown, RabbitPublisher publisher, MeterRegistry meters,
                                 Runnable recover, Runnable recovered) {
        this.mapper = mapper;
        this.discord = discord;
        this.webhookUrl = webhookUrl;
        this.cooldown = cooldown;
        this.publisher = publisher;
        this.meters = meters;
        this.recover = recover;
        this.recovered = recovered;
    }

    @Override
    public void onMessage(Message message, Channel channel) {
        long started = System.nanoTime();
        try {
            deliverOrTransfer(message);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            recovered.run();
        } catch (Exception exception) {
            count("transfer_or_ack_failure");
            // Stop first: manual-ACK deliveries remain unacknowledged and are returned when the channel closes.
            recover.run();
        } finally {
            meters.timer("report.discord.processing").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void deliverOrTransfer(Message original) throws Exception {
        int retryCount;
        long nextAttemptAt;
        String payload;
        try {
            retryCount = Math.toIntExact(headerLong(original, RETRY_COUNT));
            nextAttemptAt = headerLong(original, NEXT_ATTEMPT_AT);
            if (retryCount > 3) throw new IllegalArgumentException("Invalid retry count");
            payload = discordPayload(original);
        } catch (RuntimeException exception) {
            transfer(original, DLX, 0, 0, "InvalidEnvelope");
            return;
        }
        try {
            if (cooldown.remainingMs(nextAttemptAt) > 0) {
                transfer(original, RETRY, retryCount, nextAttemptAt, "Cooldown");
                return;
            }
        } catch (org.springframework.dao.DataAccessException | IllegalStateException exception) {
            transfer(original, RETRY, retryCount, nextAttemptAt, "RedisUnavailable");
            return;
        }
        count("attempt");
        DiscordWebhookClient.DeliveryResult result;
        try {
            result = discord.send(webhookUrl, payload);
        } catch (RuntimeException exception) {
            result = new DiscordWebhookClient.DeliveryResult(false, false, 0, false, "Unclassified");
        }
        if (result.success()) {
            count("success");
            return;
        }
        long due = 0;
        if (result.retryAfterMs() > 0) {
            due = Instant.now().toEpochMilli() + result.retryAfterMs();
            try {
                due = cooldown.extend(result.retryAfterMs(), result.global());
            } catch (org.springframework.dao.DataAccessException | IllegalStateException exception) {
                transfer(original, RETRY, retryCount, due, "RedisUnavailable");
                return;
            }
        }
        if (result.permanent() || retryCount >= 3) {
            transfer(original, DLX, retryCount, 0, result.errorCode());
        } else {
            transfer(original, RETRY, retryCount + 1, due, result.errorCode());
        }
    }

    private void transfer(Message original, String exchange, int retryCount, long due, String errorCode)
        throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.getHeaders().putAll(original.getMessageProperties().getHeaders());
        properties.setMessageId(original.getMessageProperties().getMessageId());
        properties.setType(original.getMessageProperties().getType());
        properties.setContentType(original.getMessageProperties().getContentType());
        properties.setContentEncoding(original.getMessageProperties().getContentEncoding());
        properties.setHeader("delivery.channel", "discord");
        properties.setHeader("delivery.originalQueue", DISCORD_QUEUE);
        properties.setHeader(RETRY_COUNT, retryCount);
        properties.setHeader(NEXT_ATTEMPT_AT, due);
        properties.setHeader("delivery.errorCode", errorCode);
        properties.setHeader("delivery.failedAt", Instant.now().toEpochMilli());
        RabbitPublisher.Result published = publisher.publish(new Message(original.getBody(), properties),
            UUID.randomUUID().toString(), exchange, DISCORD).get(6, TimeUnit.SECONDS);
        if (!published.confirmed()) throw new IOException("Report transfer unconfirmed");
        String outcome = DLX.equals(exchange) ? "dlq" : switch (errorCode) {
            case "RedisUnavailable" -> "redis_wait";
            case "Cooldown" -> "cooldown";
            default -> "retry";
        };
        count(outcome);
    }

    private long headerLong(Message message, String name) {
        Object header = message.getMessageProperties().getHeaders().get(name);
        if (header == null) return 0;
        if (!(header instanceof Byte || header instanceof Short || header instanceof Integer || header instanceof Long)
            || ((Number) header).longValue() < 0) throw new IllegalArgumentException("Invalid delivery metadata");
        return ((Number) header).longValue();
    }

    private String discordPayload(Message message) {
        if (message.getBody().length > 32768) throw new IllegalArgumentException("Oversized envelope");
        MessageEnvelope envelope = mapper.readValue(message.getBody(), MessageEnvelope.class);
        if (envelope.schemaVersion() != 1 || !EVENT_TYPE.equals(envelope.eventType())
            || !"REPORT".equals(envelope.aggregateType()) || envelope.eventId() == null
            || envelope.eventId().isBlank() || envelope.occurredAt() == null) {
            throw new IllegalArgumentException("Unsupported envelope");
        }
        JsonNode report = envelope.payload();
        if (report == null || !report.isObject()) throw new IllegalArgumentException("Missing report");
        String id = requiredText(report, "reportId");
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || !id.equals(envelope.aggregateId()) || !id.equals(envelope.deduplicationKey())) {
            throw new IllegalArgumentException("Invalid report ID");
        }
        String description = requiredText(report, "description");
        Instant.parse(requiredText(report, "reportedAt"));
        for (String field : new String[]{"category", "pageUrl", "route", "viewport", "userAgent", "email"}) {
            if (!report.path(field).isString()) throw new IllegalArgumentException("Invalid report field");
        }
        if (!report.path("userId").isNull() && !report.path("userId").isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid user ID");
        }
        var payload = mapper.createObjectNode();
        payload.putObject("allowed_mentions").putArray("parse");
        var embed = payload.putArray("embeds").addObject();
        embed.put("title", "서비스 제보 · " + truncate(report.path("category").asText(), 100));
        embed.put("description", truncate(description, 4096));
        var fields = embed.putArray("fields");
        fields.addObject().put("name", "reportId").put("value", id);
        for (String field : new String[]{"pageUrl", "route", "viewport", "userAgent", "reportedAt"}) {
            String value = report.path(field).asText("");
            if (!value.isBlank()) fields.addObject().put("name", field).put("value", truncate(value, 240));
        }
        String reporter = report.path("userId").isNull() ? "익명"
            : "사용자 ID: " + report.path("userId").asText() + "\n이메일: " + report.path("email").asText();
        fields.addObject().put("name", "제보자").put("value", truncate(reporter, 400));
        return mapper.writeValueAsString(payload);
    }

    private String requiredText(JsonNode payload, String field) {
        if (!payload.path(field).isString() || payload.path(field).asText().isBlank()) {
            throw new IllegalArgumentException("Missing report field");
        }
        return payload.path(field).asText();
    }

    private String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 2)) ? limit - 2 : limit - 1;
        return text.substring(0, end) + "…";
    }

    private void count(String outcome) {
        meters.counter("report.discord.deliveries", "outcome", outcome).increment();
    }
}
