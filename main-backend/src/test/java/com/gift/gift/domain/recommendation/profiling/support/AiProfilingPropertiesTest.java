package com.gift.gift.domain.recommendation.profiling.support;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AiProfilingPropertiesTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 50, 100})
    @DisplayName("MQ 배치 크기는 1~100을 허용한다")
    void acceptsBatchSizesWithinLimit(int size) {
        assertThat(properties(size).batchSize()).isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    @DisplayName("MQ 배치 크기 범위를 벗어나면 기동 설정을 거부한다")
    void rejectsInvalidBatchSizes(int size) {
        assertThatThrownBy(() -> properties(size)).isInstanceOf(IllegalArgumentException.class);
    }

    private AiProfilingProperties properties(int size) {
        return new AiProfilingProperties(URI.create("http://ai.test"), "test-token",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1),
                Duration.ofHours(6), size);
    }
}
