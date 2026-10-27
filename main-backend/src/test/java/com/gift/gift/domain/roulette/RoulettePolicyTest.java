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
    @DisplayName("전체 난수 구간에서 2배 35%, 3배 15%, 꽝 50%의 여섯 칸을 보장한다")
    void select_matchesPublishedProbabilitiesAcrossEveryValue() {
        int[] counts = new int[6];
        for (int value = 0; value < 10_000; value++) {
            counts[RoulettePolicy.select(value).segmentId()]++;
        }

        assertThat(counts).containsExactly(1750, 1500, 1667, 1750, 1667, 1666);
        assertThat(counts[0] + counts[3]).isEqualTo(3500);
        assertThat(counts[1]).isEqualTo(1500);
        assertThat(counts[2] + counts[4] + counts[5]).isEqualTo(5000);
        assertThat(Arrays.stream(counts).sum()).isEqualTo(10_000);
        assertThat(RoulettePolicy.SEGMENTS.stream().map(RoulettePolicy.Segment::probability)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(RoulettePolicy.SEGMENTS.get(5).rewardType()).isEqualTo(RouletteRewardType.MISS);
        for (var segment : RoulettePolicy.SEGMENTS) {
            assertThat(segment.probability()).isEqualByComparingTo(BigDecimal.valueOf(counts[segment.segmentId()], 2));
        }
    }

    @Test
    @DisplayName("각 확률 구간의 시작과 마지막 값이 지정한 칸에 대응한다")
    void select_handlesSegmentBoundaries() {
        int[] starts = {0, 1750, 3250, 4917, 6667, 8334};
        int[] ends = {1749, 3249, 4916, 6666, 8333, 9999};
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
