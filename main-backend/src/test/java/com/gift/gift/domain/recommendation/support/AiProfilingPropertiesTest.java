package com.gift.gift.domain.recommendation.support;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class AiProfilingPropertiesTest {
    @ParameterizedTest
    @CsvSource({"100, 0", "100, 50", "150, 50", "1, 199", "200, 0"})
    void acceptsBatchSizesWithinQueueLimit(int normal, int recovery) {
        assertThat(properties(normal, recovery).recoveryBatchSize()).isEqualTo(recovery);
    }

    @ParameterizedTest
    @CsvSource({"0, 50", "100, -1", "151, 50", "200, 1", "2147483647, 2147483647"})
    void rejectsInvalidBatchSizesIncludingOverflow(int normal, int recovery) {
        assertThatThrownBy(() -> properties(normal, recovery)).isInstanceOf(IllegalArgumentException.class);
    }

    private AiProfilingProperties properties(int normal, int recovery) {
        return new AiProfilingProperties(URI.create("http://ai.test"), "test-token",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1),
                Duration.ofHours(6), normal, recovery);
    }
}
