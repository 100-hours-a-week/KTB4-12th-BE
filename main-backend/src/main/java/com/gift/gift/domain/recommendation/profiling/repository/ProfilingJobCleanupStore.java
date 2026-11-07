package com.gift.gift.domain.recommendation.profiling.repository;

import java.time.LocalDateTime;
import java.util.Objects;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus;

@Repository
@RequiredArgsConstructor
public class ProfilingJobCleanupStore {

    private final JdbcTemplate jdbc;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public int deleteBatch(
            ProfilingResultStatus resultStatus,
            LocalDateTime finishedBefore,
            int batchSize
    ) {
        Objects.requireNonNull(resultStatus, "결과 상태는 필수입니다.");
        Objects.requireNonNull(finishedBefore, "정리 기준 시각은 필수입니다.");

        if (resultStatus != ProfilingResultStatus.COMPLETED
                && resultStatus != ProfilingResultStatus.FAILED) {
            throw new IllegalArgumentException(
                    "COMPLETED 또는 FAILED 작업만 정리할 수 있습니다."
            );
        }

        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "정리 배치 크기는 1~1000이어야 합니다."
            );
        }

        return jdbc.update("""
                DELETE FROM profiling_jobs
                WHERE result_status = ?
                  AND finished_at IS NOT NULL
                  AND finished_at < ?
                  AND publish_status = 'PUBLISHED'
                  AND published_at IS NOT NULL
                  AND claim_token IS NULL
                  AND claimed_at IS NULL
                  AND next_attempt_at IS NULL
                ORDER BY finished_at ASC, request_id ASC
                LIMIT ?
                """,
                resultStatus.name(),
                finishedBefore,
                batchSize
        );
    }
}
