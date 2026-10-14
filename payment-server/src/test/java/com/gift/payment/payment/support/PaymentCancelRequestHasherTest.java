package com.gift.payment.payment.support;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCancelRequestHasherTest {

    private final PaymentCancelRequestHasher hasher =
            new PaymentCancelRequestHasher();

    @Test
    @DisplayName("같은 주문번호는 같은 취소 요청 해시를 생성한다")
    void hash_returnsSameHash() {
        String orderId = UUID.randomUUID().toString();

        assertThat(hasher.hash(orderId))
                .isEqualTo(hasher.hash(orderId))
                .hasSize(64);
    }

    @Test
    @DisplayName("주문번호가 다르면 다른 취소 요청 해시를 생성한다")
    void hash_returnsDifferentHash() {
        assertThat(hasher.hash(UUID.randomUUID().toString()))
                .isNotEqualTo(
                        hasher.hash(UUID.randomUUID().toString())
                );
    }
}
