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

    private PaymentCreateRequest request(int quantity) {
        return new PaymentCreateRequest(
                101L,
                27L,
                quantity,
                32_000L
        );
    }
}
