package com.gift.gift.domain.recommendation.profiling.support;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;

@Component
@RequiredArgsConstructor
public class ProfilingMessageSerializer {

    public static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;

    public Snapshot serialize(ProfilingRequestMessage message) {
        Objects.requireNonNull(message, "메시지는 필수입니다.");

        final String json;

        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "프로파일링 메시지 직렬화에 실패했습니다.",
                    exception
            );
        }

        int byteSize = json.getBytes(StandardCharsets.UTF_8).length;

        return new Snapshot(
                json,
                byteSize,
                byteSize > MAX_MESSAGE_BYTES
        );
    }

    public record Snapshot(
            String json,
            int byteSize,
            boolean tooLarge
    ) {
    }
}
