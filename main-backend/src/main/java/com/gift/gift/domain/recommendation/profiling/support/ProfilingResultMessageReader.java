package com.gift.gift.domain.recommendation.profiling.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Data;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Error;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Outcome;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Payload;

@Component
public class ProfilingResultMessageReader {

    // HTTP와 요청 발행에 사용하는 공통 ObjectMapper 정책은 변경하지 않는다.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ProfilingResultMessage read(byte[] body) {
        final JsonNode root;

        try {
            root = mapper.readTree(body);
        } catch (JacksonException exception) {
            // 파서 예외에 입력 일부가 포함될 수 있어 원문을 전달하지 않는다.
            throw new IllegalArgumentException(
                    "결과 메시지가 올바른 단일 JSON이 아닙니다."
            );
        }

        requireObject(root, "message");

        long schemaVersion = positiveInteger(
                root.path("schemaVersion"), "schemaVersion"
        );

        if (schemaVersion != 1) {
            throw new IllegalArgumentException(
                    "지원하지 않는 결과 메시지 버전입니다."
            );
        }

        JsonNode payload = root.path("payload");
        requireObject(payload, "payload");

        Outcome outcome = switch (
                text(payload.path("outcome"), "payload.outcome")
                ) {
            case "SUCCEEDED" -> Outcome.SUCCEEDED;
            case "FAILED" -> Outcome.FAILED;
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 최종 결과 상태입니다."
            );
        };

        Data data = null;
        Error error = null;

        if (outcome == Outcome.SUCCEEDED) {
            if (payload.has("error")) {
                throw new IllegalArgumentException(
                        "성공 결과에 error를 포함할 수 없습니다."
                );
            }

            JsonNode resultData = payload.path("data");
            requireObject(resultData, "payload.data");

            JsonNode productIds = resultData.path(
                    "recommendedProductIds"
            );

            if (!productIds.isArray() || productIds.size() > 30) {
                throw new IllegalArgumentException(
                        "recommendedProductIds는 최대 30개 배열이어야 합니다."
                );
            }

            List<Long> ids = new ArrayList<>();

            for (JsonNode productId : productIds) {
                ids.add(positiveInteger(
                        productId,
                        "payload.data.recommendedProductIds"
                ));
            }

            data = new Data(ids);
        } else {
            if (payload.has("data")) {
                throw new IllegalArgumentException(
                        "실패 결과에 data를 포함할 수 없습니다."
                );
            }

            JsonNode failure = payload.path("error");
            requireObject(failure, "payload.error");

            error = new Error(
                    text(failure.path("code"), "payload.error.code"),
                    text(failure.path("message"), "payload.error.message")
            );
        }

        return new ProfilingResultMessage(
                1,
                text(root.path("requestId"), "requestId"),
                text(root.path("createdAt"), "createdAt"),
                new Payload(
                        positiveInteger(
                                payload.path("recipientUserId"),
                                "payload.recipientUserId"
                        ),
                        positiveInteger(
                                payload.path("sourceVersion"),
                                "payload.sourceVersion"
                        ),
                        outcome,
                        data,
                        error
                )
        );
    }

    private void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    field + "는 JSON 객체여야 합니다."
            );
        }
    }

    private String text(JsonNode node, String field) {
        if (!node.isString() || node.stringValue().isBlank()) {
            throw new IllegalArgumentException(
                    field + "는 공백 아닌 문자열이어야 합니다."
            );
        }

        return node.stringValue();
    }

    private long positiveInteger(JsonNode node, String field) {
        if (!node.isIntegralNumber()
                || !node.canConvertToLong()
                || node.longValue() < 1) {
            throw new IllegalArgumentException(
                    field + "는 양의 signed 64-bit 정수여야 합니다."
            );
        }

        return node.longValue();
    }
}
