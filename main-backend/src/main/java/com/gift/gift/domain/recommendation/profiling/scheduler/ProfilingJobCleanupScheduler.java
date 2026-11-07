package com.gift.gift.domain.recommendation.profiling.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobCleanupStore;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.ai-profile",
        name = "cleanup-enabled",
        havingValue = "true"
)
public class ProfilingJobCleanupScheduler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final int BATCH_SIZE = 1000;

    private final ProfilingJobCleanupStore cleanupStore;
    private final Clock clock;

    @Scheduled(
            cron = "0 30 3 * * *",
            zone = "Asia/Seoul"
    )
    @SchedulerLock(
            name = "cleanupProfilingJobs",
            lockAtMostFor = "PT1H",
            lockAtLeastFor = "PT1M"
    )
    public void cleanupFinishedJobs() {
        LocalDateTime executedAt = LocalDateTime
                .ofInstant(clock.instant(), ZONE)
                .truncatedTo(ChronoUnit.MICROS);

        cleanupStatus(
                ProfilingResultStatus.COMPLETED,
                executedAt.minusDays(7)
        );

        cleanupStatus(
                ProfilingResultStatus.FAILED,
                executedAt.minusDays(30)
        );
    }

    private void cleanupStatus(
            ProfilingResultStatus resultStatus,
            LocalDateTime finishedBefore
    ) {
        long deletedCount = 0;
        long committedBatches = 0;

        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn(
                            "프로파일링 작업 정리가 중단됐습니다. "
                                    + "resultStatus={}, "
                                    + "committedDeletedCount={}, "
                                    + "committedBatches={}",
                            resultStatus,
                            deletedCount,
                            committedBatches
                    );
                    return;
                }

                int deleted = cleanupStore.deleteBatch(
                        resultStatus,
                        finishedBefore,
                        BATCH_SIZE
                );

                deletedCount += deleted;

                if (deleted > 0) {
                    committedBatches++;
                }

                if (deleted < BATCH_SIZE) {
                    break;
                }
            }

            log.info(
                    "프로파일링 종료 작업 정리를 완료했습니다. "
                            + "resultStatus={}, finishedBefore={}, "
                            + "deletedCount={}, committedBatches={}",
                    resultStatus,
                    finishedBefore,
                    deletedCount,
                    committedBatches
            );
        } catch (RuntimeException exception) {
            log.error(
                    "프로파일링 종료 작업 정리가 일부 실패했습니다. "
                            + "resultStatus={}, finishedBefore={}, "
                            + "committedDeletedCount={}, "
                            + "committedBatches={}",
                    resultStatus,
                    finishedBefore,
                    deletedCount,
                    committedBatches,
                    exception
            );
        }
    }
}
