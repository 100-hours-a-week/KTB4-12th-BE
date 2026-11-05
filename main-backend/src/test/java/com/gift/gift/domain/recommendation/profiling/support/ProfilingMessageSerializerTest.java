package com.gift.gift.domain.recommendation.profiling.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;

import static org.assertj.core.api.Assertions.*;

class ProfilingMessageSerializerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfilingMessageSerializer serializer = new ProfilingMessageSerializer(mapper);

    private ProfilingRequestMessage message(String preference) {
        return ProfilingRequestMessage.create(
                UUID.fromString("83ae0a27-0ddc-4fb0-a587-35e4937e4f74"),
                Instant.parse("2026-11-05T00:00:00Z"),
                new AiProfileRequest(1L, 1, List.of(), preference, List.of()));
    }

    @Test
    @DisplayName("직렬화 시 페이로드의 null과 배열을 유지하고 선택 항목인 추적 ID는 생략한다")
    void keepsExistingPayloadNullsAndOmitsOptionalTrace() {
        var snapshot = serializer.serialize(message(null));
        var root = mapper.readTree(snapshot.json());
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.path("createdAt").asText()).isEqualTo("2026-11-05T00:00:00Z");
        assertThat(root.has("traceId")).isFalse();
        assertThat(root.path("payload").has("giftPreference")).isTrue();
        assertThat(root.path("payload").path("giftPreference").isNull()).isTrue();
        assertThat(root.path("payload").path("reviews").isArray()).isTrue();
        assertThat(snapshot.byteSize()).isEqualTo(snapshot.json().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("메시지 전체 크기가 제한과 같으면 허용하고 1바이트 초과하면 크기 초과로 판단한다")
    void acceptsExactLimitAndRejectsOneByteOverIncludingEnvelope() {
        int overhead = serializer.serialize(message("")).byteSize();
        String input = "a".repeat(ProfilingMessageSerializer.MAX_MESSAGE_BYTES - overhead);
        assertThat(serializer.serialize(message(input)).tooLarge()).isFalse();
        assertThat(serializer.serialize(message(input + "a")).tooLarge()).isTrue();
    }

    @Test
    @DisplayName("한글 메시지 크기는 문자 수가 아닌 UTF-8 바이트 수로 계산한다")
    void countsKoreanAsUtf8BytesRatherThanCharacters() {
        var snapshot = serializer.serialize(message("한".repeat(90_000)));
        assertThat(snapshot.json().length()).isLessThan(ProfilingMessageSerializer.MAX_MESSAGE_BYTES);
        assertThat(snapshot.tooLarge()).isTrue();
    }

    @Test
    @DisplayName("지원하지 않는 스키마 버전과 0인 원본 버전은 거부한다")
    void rejectsUnsupportedVersionAndZeroRequestVersion() {
        var valid = message(null);
        assertThatThrownBy(() -> new ProfilingRequestMessage(2, valid.requestId(),
                valid.createdAt(), null, valid.payload())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfilingRequestMessage.create(UUID.randomUUID(), Instant.now(),
                new AiProfileRequest(1L, 0, List.of(), null, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
