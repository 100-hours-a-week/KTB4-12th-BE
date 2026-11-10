package com.gift.gift.global.messaging;

import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

public record MessageEnvelope(
        String eventId,
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
}
