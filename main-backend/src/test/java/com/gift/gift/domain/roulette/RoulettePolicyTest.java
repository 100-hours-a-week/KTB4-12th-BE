package com.gift.gift.domain.roulette;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gift.gift.domain.roulette.entity.RouletteRewardType;
import com.gift.gift.domain.roulette.support.RoulettePolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoulettePolicyTest {

    @Test
    @DisplayName("전체 난수 구간에서 2배 48.75%, 3배 16.25%, 꽝 35%, 박스 0%를 보장한다")
    void select_matchesPublishedProbabilitiesAcrossEveryValue() {
        int[] counts = new int[6];
        for (int value = 0; value < 10_000; value++) {
            counts[RoulettePolicy.select(value).segmentId()]++;
        }

        assertThat(counts).containsExactly(2438, 1625, 1750, 2437, 1750, 0);
        assertThat(counts[0] + counts[3]).isEqualTo(4875);
        assertThat(Arrays.stream(counts).sum()).isEqualTo(10_000);
        assertThat(RoulettePolicy.SEGMENTS.stream().map(RoulettePolicy.Segment::probability)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(RoulettePolicy.SEGMENTS.get(5).rewardType()).isEqualTo(RouletteRewardType.RANDOM_BOX);
        for (var segment : RoulettePolicy.SEGMENTS) {
            assertThat(segment.probability()).isEqualByComparingTo(BigDecimal.valueOf(counts[segment.segmentId()], 2));
        }
    }

    @Test
    @DisplayName("각 확률 구간의 시작과 마지막 값이 지정한 칸에 대응한다")
    void select_handlesSegmentBoundaries() {
        int[] starts = {0, 2438, 4063, 5813, 8250};
        int[] ends = {2437, 4062, 5812, 8249, 9999};
        for (int id = 0; id < starts.length; id++) {
            assertThat(RoulettePolicy.select(starts[id]).segmentId()).isEqualTo(id);
            assertThat(RoulettePolicy.select(ends[id]).segmentId()).isEqualTo(id);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10000, Integer.MAX_VALUE})
    @DisplayName("유효 난수 구간 밖의 값은 거부한다")
    void select_rejectsOutOfRangeValues(int value) {
        assertThatThrownBy(() -> RoulettePolicy.select(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
