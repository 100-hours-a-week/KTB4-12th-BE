package com.gift.gift.domain.bugreport.messaging;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
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

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;

public class ReportConsumer implements ChannelAwareMessageListener {
    public static final String RETRY_COUNT = "delivery.retryCount";
    public static final String NEXT_ATTEMPT_AT = "delivery.nextAttemptAt";
    private final ObjectMapper mapper;
    private final String channelName;
    private final String routingKey;
    private final String queue;
    private final Function<JsonNode, ReportDeliveryResult> delivery;
    private final ReportCooldown cooldown;
    private final RabbitPublisher publisher;
    private final MeterRegistry meters;
    private final Runnable recover;
    private final Runnable recovered;

    public ReportConsumer(ObjectMapper mapper, String channelName, String routingKey, String queue,
                          Function<JsonNode, ReportDeliveryResult> delivery, ReportCooldown cooldown,
                          RabbitPublisher publisher, MeterRegistry meters, Runnable recover, Runnable recovered) {
        this.mapper = mapper;
        this.channelName = channelName;
        this.routingKey = routingKey;
        this.queue = queue;
        this.delivery = delivery;
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
            meters.timer("report." + channelName + ".processing").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void deliverOrTransfer(Message original) throws Exception {
        int retryCount;
        long nextAttemptAt;
        JsonNode payload;
        try {
            retryCount = Math.toIntExact(headerLong(original, RETRY_COUNT));
            nextAttemptAt = headerLong(original, NEXT_ATTEMPT_AT);
            if (retryCount > 3) throw new IllegalArgumentException("Invalid retry count");
            payload = reportPayload(original);
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
        ReportDeliveryResult result;
        try {
            result = delivery.apply(payload);
        } catch (RuntimeException exception) {
            result = new ReportDeliveryResult(false, false, 0, false, "Unclassified");
        }
        if (result.success()) {
            count("success");
            if ("AlreadyRecorded".equals(result.errorCode())) count("already_recorded");
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
        properties.setHeader("delivery.channel", channelName);
        properties.setHeader("delivery.originalQueue", queue);
        properties.setHeader(RETRY_COUNT, retryCount);
        properties.setHeader(NEXT_ATTEMPT_AT, due);
        properties.setHeader("delivery.errorCode", errorCode);
        properties.setHeader("delivery.failedAt", Instant.now().toEpochMilli());
        RabbitPublisher.Result published = publisher.publish(new Message(original.getBody(), properties),
            UUID.randomUUID().toString(), exchange, routingKey).get(6, TimeUnit.SECONDS);
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

    private JsonNode reportPayload(Message message) {
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
        requiredText(report, "description");
        Instant.parse(requiredText(report, "reportedAt"));
        for (String field : new String[]{"category", "pageUrl", "route", "viewport", "userAgent", "email"}) {
            if (!report.path(field).isString()) throw new IllegalArgumentException("Invalid report field");
        }
        if (!report.path("userId").isNull() && !report.path("userId").isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid user ID");
        }
        return report;
    }

    private String requiredText(JsonNode payload, String field) {
        if (!payload.path(field).isString() || payload.path(field).asText().isBlank()) {
            throw new IllegalArgumentException("Missing report field");
        }
        return payload.path(field).asText();
    }

    private void count(String outcome) {
        meters.counter("report." + channelName + ".deliveries", "outcome", outcome).increment();
    }
}
