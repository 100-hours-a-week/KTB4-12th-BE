package com.gift.gift.domain.recommendation.profiling.support;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

public final class ProfilingPublishPolicy {

    public static final int MAX_ATTEMPTS = 3;
    public static final int CLAIM_SECONDS = 120;
    public static final int FAILURE_BUDGET_MINUTES = 30;

    private ProfilingPublishPolicy() {
    }

    public static boolean exhausted(
            int attempts,
            LocalDateTime firstFailedAt,
            LocalDateTime now
    ) {
        return attempts >= MAX_ATTEMPTS
                || (
                firstFailedAt != null
                        && !now.isBefore(
                        firstFailedAt.plusMinutes(FAILURE_BUDGET_MINUTES)
                )
        );
    }

    public static long retryDelayMillis(int attempts) {
        // 1회 실패 후 1분, 2회 실패 후 2분.
        long baseMillis = Math.min(
                300_000L,
                60_000L * (1L << Math.min(
                        Math.max(attempts - 1, 0),
                        2
                ))
        );

        double jitter = ThreadLocalRandom.current()
                .nextDouble(0.8, 1.2);

        return Math.round(baseMillis * jitter);
    }
}
