package com.gift.gift.domain.recommendation.profiling.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

class ProfilingResultMessageReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfilingResultMessageReader reader = new ProfilingResultMessageReader();

    @ParameterizedTest
    @ValueSource(strings = {"succeeded", "empty", "failed"})
    @DisplayName("성공·빈 추천·실패 예제를 읽고 동일한 결과 계약으로 직렬화한다")
    void readsContractFixtures(String name) throws IOException {
        byte[] body = fixture(name);
        var result = reader.read(body);
        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.payload().recipientUserId()).isEqualTo(123);
        assertThat(result.payload().sourceVersion()).isEqualTo(7);
        assertThat(mapper.readTree(mapper.writeValueAsBytes(result))).isEqualTo(mapper.readTree(body));
        if (result.payload().data() != null) {
            assertThatThrownBy(() -> result.payload().data().recommendedProductIds().add(999L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "requestId", "createdAt", "payload"})
    @DisplayName("봉투의 필수 필드는 생략과 null 모두 거부한다")
    void rejectsMissingEnvelopeFields(String field) throws IOException {
        ObjectNode root = success();
        root.remove(field);
        assertInvalid(root.toString());
        root.putNull(field);
        assertInvalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipientUserId", "sourceVersion", "outcome", "data"})
    @DisplayName("성공 payload 필수 필드는 생략과 null 모두 거부한다")
    void rejectsMissingPayloadFields(String field) throws IOException {
        ObjectNode root = success();
        ObjectNode payload = (ObjectNode) root.path("payload");
        payload.remove(field);
        assertInvalid(root.toString());
        payload.putNull(field);
        assertInvalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "1.0", "1.5", "true", "null", "{}", "[]", "0", "-1",
            "9223372036854775808"})
    @DisplayName("정수 필드와 상품 ID는 형 변환·범위 초과를 허용하지 않는다")
    void rejectsInvalidIntegerTokens(String token) throws IOException {
        ObjectNode root = success();
        root.set("schemaVersion", mapper.readTree(token));
        assertInvalid(root.toString());
        for (String field : new String[]{"recipientUserId", "sourceVersion"}) {
            root = success();
            ((ObjectNode) root.path("payload")).set(field, mapper.readTree(token));
            assertInvalid(root.toString());
        }
        root = success();
        ((ObjectNode) root.path("payload").path("data"))
                .set("recommendedProductIds", mapper.readTree("[" + token + "]"));
        assertInvalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "\"[1]\"", "[1,1]", "[null]"})
    @DisplayName("추천 배열 타입·중복·null 원소 오류를 거부한다")
    void rejectsInvalidProductLists(String ids) throws IOException {
        ObjectNode root = success();
        ((ObjectNode) root.path("payload").path("data")).set("recommendedProductIds", mapper.readTree(ids));
        assertInvalid(root.toString());
    }

    @Test
    @DisplayName("30개와 Long 최댓값은 허용하고 31개는 거부한다")
    void validatesProductCountAndLongBoundary() throws IOException {
        ObjectNode root = success();
        var payload = (ObjectNode) root.path("payload");
        payload.put("recipientUserId", Long.MAX_VALUE);
        payload.put("sourceVersion", Long.MAX_VALUE);
        var ids = ((ObjectNode) payload.path("data")).putArray("recommendedProductIds");
        LongStream.rangeClosed(1, 29).forEach(ids::add);
        ids.add(Long.MAX_VALUE);
        assertThat(reader.read(mapper.writeValueAsBytes(root)).payload().data().recommendedProductIds()).hasSize(30);
        ids.add(30);
        assertInvalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "", "83ae0a27-0ddc-4fb0-a587-35e4937e4f74 "})
    @DisplayName("축약 UUID·잘못된 요청 ID를 거부한다")
    void rejectsInvalidRequestIds(String requestId) throws IOException {
        ObjectNode root = success();
        root.put("requestId", requestId);
        assertInvalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-03T05:00:15", "2026-11-03T05:00:15+09:00", "invalid", ""})
    @DisplayName("생성 시각은 UTC Instant 문자열이어야 한다")
    void rejectsInvalidTimestamps(String timestamp) throws IOException {
        ObjectNode root = success();
        root.put("createdAt", timestamp);
        assertInvalid(root.toString());
    }

    @Test
    @DisplayName("성공과 실패의 data·error 혼용 및 미지원 상태·스키마를 거부한다")
    void rejectsInvalidOutcomesAndShapes() throws IOException {
        ObjectNode root = success();
        var payload = (ObjectNode) root.path("payload");
        payload.putNull("error");
        assertInvalid(root.toString());
        payload.remove("error");
        for (String outcome : new String[]{"FAILED", "COMPLETED", "PENDING", "succeeded"}) {
            payload.put("outcome", outcome);
            assertInvalid(root.toString());
        }
        root = success();
        root.put("schemaVersion", 2);
        assertInvalid(root.toString());
    }

    @Test
    @DisplayName("실패 코드·설명은 필수 문자열이며 아직 합의하지 않은 코드도 보존한다")
    void validatesFailureFieldsWithoutInventingCodeAllowlist() throws IOException {
        var root = (ObjectNode) mapper.readTree(fixture("failed"));
        var error = (ObjectNode) root.path("payload").path("error");
        error.put("code", "AI_FUTURE_CODE");
        assertThat(reader.read(mapper.writeValueAsBytes(root)).payload().error().code()).isEqualTo("AI_FUTURE_CODE");
        for (String field : new String[]{"code", "message"}) {
            var invalid = root.deepCopy();
            var invalidError = (ObjectNode) invalid.path("payload").path("error");
            invalidError.remove(field);
            assertInvalid(invalid.toString());
            for (String token : new String[]{"null", "1", "true", "\" \""}) {
                invalidError.set(field, mapper.readTree(token));
                assertInvalid(invalid.toString());
            }
        }
        ((ObjectNode) root.path("payload")).putNull("data");
        assertInvalid(root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "1", "true", "{", "{} {}"})
    @DisplayName("비객체·깨진 JSON·여러 JSON 본문은 거부한다")
    void rejectsMalformedBodies(String json) {
        assertInvalid(json);
    }

    @Test
    @DisplayName("중복 키는 중첩 깊이와 관계없이 거부하고 알 수 없는 필드는 무시한다")
    void rejectsDuplicateKeysButIgnoresUnknownFields() throws IOException {
        String json = new String(fixture("succeeded"), StandardCharsets.UTF_8);
        assertInvalid(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"));
        assertInvalid(json.replace("\"sourceVersion\": 7", "\"sourceVersion\": 7, \"sourceVersion\": 7"));
        ObjectNode root = success();
        root.put("futureField", "ignored");
        ((ObjectNode) root.path("payload").path("data")).put("futureField", 1);
        assertThat(reader.read(mapper.writeValueAsBytes(root))).isEqualTo(reader.read(fixture("succeeded")));
    }

    private ObjectNode success() throws IOException {
        return (ObjectNode) mapper.readTree(fixture("succeeded"));
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/profiling/result-" + name + ".json")) {
            return input.readAllBytes();
        }
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> reader.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
