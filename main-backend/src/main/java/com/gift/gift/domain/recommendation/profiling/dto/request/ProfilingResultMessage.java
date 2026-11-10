package com.gift.gift.domain.recommendation.profiling.dto.request;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfilingResultMessage(
        int schemaVersion,
        String requestId,
        String createdAt,
        Payload payload
) {

    public ProfilingResultMessage {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException(
                    "지원하지 않는 결과 메시지 버전입니다."
            );
        }

        Objects.requireNonNull(requestId, "요청 ID는 필수입니다.");
        Objects.requireNonNull(createdAt, "결과 생성 시각은 필수입니다.");
        Objects.requireNonNull(payload, "결과 payload는 필수입니다.");

        if (!UUID.fromString(requestId).toString()
                .equalsIgnoreCase(requestId)) {
            throw new IllegalArgumentException(
                    "요청 ID는 표준 UUID 형식이어야 합니다."
            );
        }

        try {
            Instant.parse(createdAt);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "결과 생성 시각 형식이 올바르지 않습니다."
            );
        }

        if (!createdAt.endsWith("Z")) {
            throw new IllegalArgumentException(
                    "결과 생성 시각은 UTC Instant 형식이어야 합니다."
            );
        }
    }

    public enum Outcome {
        SUCCEEDED,
        FAILED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payload(
            long recipientUserId,
            long sourceVersion,
            Outcome outcome,
            Data data,
            Error error
    ) {
        public Payload {
            if (recipientUserId < 1 || sourceVersion < 1) {
                throw new IllegalArgumentException(
                        "수신자 ID와 요청 버전은 1 이상이어야 합니다."
                );
            }

            Objects.requireNonNull(
                    outcome, "최종 결과 상태는 필수입니다."
            );

            if ((outcome == Outcome.SUCCEEDED
                    && (data == null || error != null))
                    || (outcome == Outcome.FAILED
                    && (error == null || data != null))) {
                throw new IllegalArgumentException(
                        "성공에는 data만, 실패에는 error만 필요합니다."
                );
            }
        }
    }

    public record Data(List<Long> recommendedProductIds) {
        public Data {
            recommendedProductIds = List.copyOf(
                    recommendedProductIds
            );

            if (recommendedProductIds.size() > 30
                    || recommendedProductIds.stream()
                    .anyMatch(productId -> productId < 1)
                    || new HashSet<>(recommendedProductIds).size()
                    != recommendedProductIds.size()) {
                throw new IllegalArgumentException(
                        "추천은 중복 없는 양의 상품 ID를 0~30개 포함해야 합니다."
                );
            }
        }
    }

    public record Error(String code, String message) {
        public Error {
            if (code == null || code.isBlank()
                    || message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "실패 코드와 설명은 공백 아닌 문자열이어야 합니다."
                );
            }
        }
    }
}
