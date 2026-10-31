package com.gift.gift.domain.randombox;

import java.util.EnumMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.randombox.support.RandomBoxOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RandomBoxOutcomeTest {
    @Test
    @DisplayName("전체 추첨 구간은 enum에 설정된 상품·꽝 가중치와 정확히 일치한다")
    void select_distributesEveryValueAccordingToWeights() {
        var counts = new EnumMap<RandomBoxOutcome, Integer>(RandomBoxOutcome.class);
        for (int value = 0; value < RandomBoxOutcome.totalWeight(); value++) {
            counts.merge(RandomBoxOutcome.select(value), 1, Integer::sum);
        }
        for (RandomBoxOutcome outcome : RandomBoxOutcome.values()) {
            assertThat(counts.getOrDefault(outcome, 0)).isEqualTo(outcome.weight());
        }
    }

    @Test
    @DisplayName("추첨 구간의 양 끝을 벗어나는 입력은 거부한다")
    void select_rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> RandomBoxOutcome.select(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RandomBoxOutcome.select(RandomBoxOutcome.totalWeight()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
