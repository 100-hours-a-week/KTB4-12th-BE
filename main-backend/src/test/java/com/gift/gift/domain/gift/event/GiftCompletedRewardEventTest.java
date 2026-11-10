package com.gift.gift.domain.gift.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiftCompletedRewardEventTest {

    @Test
    @DisplayName("기존 생성자는 배수 1을 사용하며 과거 지급액을 쿠폰으로 추측하지 않는다")
    void constructor_preservesLegacyRewardWithoutInferringCoupon() {
        var event = new GiftCompletedRewardEvent(1L, 2L, "선물", 100_000L);
        assertThat(event.rewardAmount()).isEqualTo(100_000L);
        assertThat(event.rewardMultiplier()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 4})
    @DisplayName("이벤트는 허용되지 않는 배수를 거부한다")
    void constructor_rejectsInvalidMultiplier(int multiplier) {
        assertThatThrownBy(() -> new GiftCompletedRewardEvent(1L, 2L, "선물", 50_000L, multiplier))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("이벤트는 양수가 아닌 지급액을 거부한다")
    void constructor_rejectsInvalidAmount(long amount) {
        assertThatThrownBy(() -> new GiftCompletedRewardEvent(1L, 2L, "선물", amount, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
