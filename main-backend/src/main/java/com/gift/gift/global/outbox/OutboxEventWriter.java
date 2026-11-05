package com.gift.gift.global.outbox;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OutboxEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(Event event) {
        Objects.requireNonNull(event, "event");
        String eventId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO outbox_event (
                    event_id, event_type, schema_version, deduplication_key,
                    aggregate_type, aggregate_id, aggregate_version, payload, occurred_at, trace_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, event.eventType(), event.schemaVersion(), event.deduplicationKey(),
                event.aggregateType(), event.aggregateId(), event.aggregateVersion(),
                mapper.writeValueAsString(event.payload()), event.occurredAt(), event.traceId());
        return eventId;
    }

    public record Event(
            String eventType,
            int schemaVersion,
            String deduplicationKey,
            String aggregateType,
            String aggregateId,
            Long aggregateVersion,
            JsonNode payload,
            LocalDateTime occurredAt,
            String traceId
    ) {
        public Event {
            requireText(eventType, 100);
            requireText(deduplicationKey, 200);
            requireText(aggregateType, 50);
            requireText(aggregateId, 100);
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(payload, "payload");
            if (!payload.isObject() || schemaVersion < 1
                    || (aggregateVersion != null && aggregateVersion < 1)
                    || (traceId != null && traceId.length() > 64)) {
                throw new IllegalArgumentException("잘못된 Outbox 메시지 계약입니다.");
            }
        }

        private static void requireText(String value, int maxLength) {
            Objects.requireNonNull(value, "Outbox 필수 값");
            if (value.isBlank() || value.length() > maxLength) {
                throw new IllegalArgumentException("Outbox 필수 값의 길이가 올바르지 않습니다.");
            }
        }
    }
}
