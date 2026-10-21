package com.gift.payment.payment.support;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestHasherTest {

    private final PaymentRequestHasher hasher =
            new PaymentRequestHasher();

    @Test
    @DisplayName("주문번호나 금액이 다르면 요청 해시도 달라진다")
    void hash_returnsDifferentHash() {
        String orderId = UUID.randomUUID().toString();

        String original = hasher.hash(orderId, 64_000L);
        String differentOrder = hasher.hash(UUID.randomUUID().toString(), 64_000L);
        String differentAmount = hasher.hash(orderId, 65_000L);

        assertThat(differentOrder).isNotEqualTo(original);
        assertThat(differentAmount).isNotEqualTo(original);
    }
}
