package com.gift.gift.domain.recommendation.profiling.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Outcome;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Payload;

@Component
public class ProfilingResultFingerprint {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String hash(ProfilingResultMessage message) {
        Objects.requireNonNull(
                message, "결과 메시지는 필수입니다."
        );

        Payload payload = message.payload();

        // 저장 후에도 비교 JSON의 필드 순서·숫자 표현·배열 순서를 유지한다.
        ObjectNode comparison = mapper.createObjectNode();
        comparison.put("outcome", payload.outcome().name());

        if (payload.outcome() == Outcome.SUCCEEDED) {
            ArrayNode ids = comparison.putArray("recommendedProductIds");
            payload.data().recommendedProductIds().forEach(ids::add);
        } else {
            comparison.put("code", payload.error().code());
        }

        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    mapper.writeValueAsString(comparison)
                                            .getBytes(StandardCharsets.UTF_8)
                            )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 해시 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
    }
}
