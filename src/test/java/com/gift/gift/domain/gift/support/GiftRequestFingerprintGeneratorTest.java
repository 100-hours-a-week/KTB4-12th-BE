package com.gift.gift.domain.gift.support;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GiftRequestFingerprintGeneratorTest {

    private final GiftRequestFingerprintGenerator generator = new GiftRequestFingerprintGenerator();

    @Test
    @DisplayName("같은 의미의 선물 생성 요청은 같은 fingerprint를 생성한다")
    void generate_returnsSameFingerprintForEquivalentRequests() {
        GiftCreateRequest first = new GiftCreateRequest(1L, 2L, 3, new BigDecimal("1000"));
        GiftCreateRequest second = new GiftCreateRequest(1L, 2L, 3, new BigDecimal("1000.0"));

        String firstFingerprint = generator.generate(first);
        String secondFingerprint = generator.generate(second);

        assertThat(firstFingerprint)
                .isEqualTo(secondFingerprint)
                .isEqualTo("2115d75094325a5a19336ab10a5d29f1fd7ab2c5672f0cbda9af1c102cc753fd")
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("선물 생성 요청 필드가 달라지면 다른 fingerprint를 생성한다")
    void generate_returnsDifferentFingerprintWhenRequestFieldChanges() {
        GiftCreateRequest original = new GiftCreateRequest(1L, 2L, 3, new BigDecimal("1000"));
        List<GiftCreateRequest> changedRequests = List.of(
                new GiftCreateRequest(9L, 2L, 3, new BigDecimal("1000")),
                new GiftCreateRequest(1L, 9L, 3, new BigDecimal("1000")),
                new GiftCreateRequest(1L, 2L, 9, new BigDecimal("1000")),
                new GiftCreateRequest(1L, 2L, 3, new BigDecimal("9000"))
        );

        String originalFingerprint = generator.generate(original);

        assertThat(changedRequests)
                .extracting(generator::generate)
                .doesNotContain(originalFingerprint)
                .doesNotHaveDuplicates();
    }
}
