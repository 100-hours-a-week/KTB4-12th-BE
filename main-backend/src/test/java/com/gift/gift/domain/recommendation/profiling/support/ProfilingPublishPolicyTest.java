package com.gift.gift.domain.recommendation.profiling.support;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilingPublishPolicyTest {
    @Test
    @DisplayName("최초 시도 포함 3회부터 발행 예산을 소진한다")
    void attempts_exhaustAtThirdAttempt() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 0, 0);
        assertThat(ProfilingPublishPolicy.exhausted(2, null, now)).isFalse();
        assertThat(ProfilingPublishPolicy.exhausted(3, null, now)).isTrue();
    }

    @Test
    @DisplayName("최초 실패 후 정확히 30분이 되면 발행 예산을 소진한다")
    void failureBudget_exhaustsAtThirtyMinutes() {
        LocalDateTime first = LocalDateTime.of(2026, 11, 5, 0, 0);
        assertThat(ProfilingPublishPolicy.exhausted(1, first, first.plusMinutes(30).minusNanos(1))).isFalse();
        assertThat(ProfilingPublishPolicy.exhausted(1, first, first.plusMinutes(30))).isTrue();
    }

    @Test
    @DisplayName("1회·2회 실패 후 재시도 지연은 각각 1분·2분의 ±20% 범위다")
    void retryDelay_staysWithinJitterBounds() {
        for (int i = 0; i < 100; i++) {
            assertThat(ProfilingPublishPolicy.retryDelayMillis(1)).isBetween(48_000L, 72_000L);
            assertThat(ProfilingPublishPolicy.retryDelayMillis(2)).isBetween(96_000L, 144_000L);
        }
    }
}
