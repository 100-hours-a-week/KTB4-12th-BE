package com.gift.gift.domain.order.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestHasherTest {

    private final PaymentRequestHasher hasher = new PaymentRequestHasher();

    @Test
    @DisplayName("같은 결제 요청은 같은 해시를 생성한다")
    void hash_returnsSameHash() {
        PaymentCreateRequest request = request(2);

        String first = hasher.hash(request);
        String second = hasher.hash(request);

        assertThat(first).hasSize(64).isEqualTo(second);
    }

    @Test
    @DisplayName("결제 요청 내용이 다르면 다른 해시를 생성한다")
    void hash_returnsDifferentHash() {
        String first = hasher.hash(request(2));
        String second = hasher.hash(request(3));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("쿠폰 미사용은 기존 해시를 유지하고 2배·3배 선택은 구분한다")
    void hash_preservesUnusedRequestAndDistinguishesCoupons() {
        PaymentCreateRequest legacy = request(2);
        PaymentCreateRequest unused = new PaymentCreateRequest(101L, 27L, 2, 32_000L, null);
        PaymentCreateRequest doubled = new PaymentCreateRequest(101L, 27L, 2, 32_000L, 2);
        PaymentCreateRequest tripled = new PaymentCreateRequest(101L, 27L, 2, 32_000L, 3);
        assertThat(hasher.hash(legacy)).isEqualTo(hasher.hash(unused));
        assertThat(hasher.hash(doubled)).isNotEqualTo(hasher.hash(unused));
        assertThat(hasher.hash(tripled)).isNotEqualTo(hasher.hash(doubled));
    }

    @Test
    @DisplayName("쿠폰을 쓰지 않으면 기존 v1 원문으로 계산한 SHA256을 유지한다")
    void hash_keepsLegacyFingerprint() throws Exception {
        String source = "v1|productId=101|recipientUserId=27|quantity=2|expectedUnitPrice=32000";
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        source.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
        );
        assertThat(hasher.hash(request(2))).isEqualTo(expected);
    }

    private PaymentCreateRequest request(int quantity) {
        return new PaymentCreateRequest(
                101L,
                27L,
                quantity,
                32_000L
        );
    }
}
