package com.gift.gift.domain.recommendation.profiling.support;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class ProfilingResultFingerprintTest {

    private final ProfilingResultMessageReader reader = new ProfilingResultMessageReader();
    private final ProfilingResultFingerprint fingerprint = new ProfilingResultFingerprint();

    @ParameterizedTest
    @CsvSource({
            "succeeded,5d477be7fc0da0393a779aae0eed00fe20fe8a03a85ffd9abd8b3c57646dca64",
            "empty,48aed36b56d977490ab8340b36b1ea4dcc4e0faae1bf9419f8ab0eddcb58b211",
            "failed,31f9eceb47061caa7d7f4c0c5c551dbd04a28d71c702190db77f23227e8bda78"
    })
    @DisplayName("고정 비교 JSON의 UTF-8 SHA-256 테스트 벡터를 유지한다")
    void matchesFixedVectors(String fixture, String expected) throws Exception {
        assertThat(hash(body(fixture))).isEqualTo(expected).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("요청 식별자·수신자·버전·생성 시각은 결과 동일성 해시에 포함하지 않는다")
    void excludesEnvelopeAndIdentityFields() throws Exception {
        String original = body("succeeded");
        String changed = original.replace("83ae0a27-0ddc-4fb0-a587-35e4937e4f74",
                        "83ae0a27-0ddc-4fb0-a587-35e4937e4f75")
                .replace("2026-11-03T05:00:15Z", "2026-11-04T05:00:15Z")
                .replace("\"recipientUserId\": 123", "\"recipientUserId\": 124")
                .replace("\"sourceVersion\": 7", "\"sourceVersion\": 8");
        assertThat(hash(changed)).isEqualTo(hash(original));
    }

    @Test
    @DisplayName("추천 순서·상품 추가/삭제·오류 코드는 상충 결과로 구분된다")
    void distinguishesChangedBusinessResults() throws Exception {
        String success = body("succeeded");
        assertThat(hash(success.replace("[103, 208]", "[208, 103]"))).isNotEqualTo(hash(success));
        assertThat(hash(success.replace("[103, 208]", "[103]"))).isNotEqualTo(hash(success));
        assertThat(hash(success.replace("[103, 208]", "[103, 208, 999]"))).isNotEqualTo(hash(success));
        String failure = body("failed");
        assertThat(hash(failure.replace("ANALYSIS_FAILED", "INPUT_INVALID"))).isNotEqualTo(hash(failure));
        assertThat(hash(failure)).isNotEqualTo(hash(success));
    }

    @Test
    @DisplayName("설명·JSON 필드 순서·공백 변경은 동일 결과로 취급한다")
    void ignoresDescriptionAndJsonFormatting() throws Exception {
        String failure = body("failed");
        assertThat(hash(failure.replace("프로파일링 분석에 실패했습니다.", "처리하지 못했습니다.")))
                .isEqualTo(hash(failure));
        assertThat(hash(failure.replace("\"schemaVersion\": 1,", "")
                .replace("\"payload\":", "\"schemaVersion\":1,\"payload\":")))
                .isEqualTo(hash(failure));
    }

    @Test
    @DisplayName("오류 코드의 따옴표·한글도 JSON 이스케이프와 UTF-8로 안정적으로 계산한다")
    void hashesEscapedUnicodeCodes() throws Exception {
        String failure = body("failed").replace("ANALYSIS_FAILED", "코드\\\"A\\nB");
        assertThat(hash(failure)).matches("[0-9a-f]{64}");
        assertThat(hash(failure.replace("A\\nB", "AB"))).isNotEqualTo(hash(failure));
    }

    private String body(String fixture) throws Exception {
        try (var input = getClass().getResourceAsStream("/profiling/result-" + fixture + ".json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String hash(String json) {
        return fingerprint.hash(reader.read(json.getBytes(StandardCharsets.UTF_8)));
    }
}
