package com.gift.gift.domain.recommendation.profiling.dto.request;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfilingRequestMessage(
        int schemaVersion,
        String requestId,
        String createdAt,
        String traceId,
        AiProfileRequest payload
) {

    public ProfilingRequestMessage {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException(
                    "지원하지 않는 요청 메시지 버전입니다."
            );
        }

        Objects.requireNonNull(requestId, "요청 ID는 필수입니다.");
        Objects.requireNonNull(createdAt, "생성 시각은 필수입니다.");
        Objects.requireNonNull(payload, "요청 payload는 필수입니다.");

        UUID.fromString(requestId);
        Instant.parse(createdAt);

        if (payload.sourceVersion() < 1) {
            throw new IllegalArgumentException(
                    "작업 요청 버전은 1 이상이어야 합니다."
            );
        }

        if (traceId != null && traceId.isBlank()) {
            throw new IllegalArgumentException(
                    "traceId는 공백일 수 없습니다."
            );
        }
    }

    public static ProfilingRequestMessage create(
            UUID requestId,
            Instant createdAt,
            AiProfileRequest payload
    ) {
        return new ProfilingRequestMessage(
                1,
                requestId.toString(),
                createdAt.toString(),
                null,
                payload
        );
    }
}
