package com.gift.gift.domain.order.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCancellationRequestHasherTest {

    private final PaymentCancellationRequestHasher hasher =
            new PaymentCancellationRequestHasher();

    @Test
    @DisplayName("같은 주문번호는 같은 취소 요청 해시를 생성한다")
    void hash_returnsSameHashForSameOrderId() {
        String orderId = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(hasher.hash(orderId))
                .isEqualTo(hasher.hash(orderId));
    }

    @Test
    @DisplayName("주문번호가 다르면 다른 취소 요청 해시를 생성한다")
    void hash_returnsDifferentHashForDifferentOrderId() {
        String firstOrderId = "550e8400-e29b-41d4-a716-446655440000";
        String secondOrderId = "550e8400-e29b-41d4-a716-446655440001";

        assertThat(hasher.hash(firstOrderId))
                .isNotEqualTo(hasher.hash(secondOrderId));
    }
}
