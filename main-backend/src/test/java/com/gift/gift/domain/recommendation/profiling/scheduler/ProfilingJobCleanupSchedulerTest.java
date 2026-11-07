package com.gift.gift.domain.recommendation.profiling.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobCleanupStore;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilingJobCleanupSchedulerTest {

    private final ProfilingJobCleanupStore store =
            mock(ProfilingJobCleanupStore.class);

    // 서울 기준 2026-11-07 03:30
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-11-06T18:30:00Z"),
            ZoneOffset.UTC
    );

    private final ProfilingJobCleanupScheduler scheduler =
            new ProfilingJobCleanupScheduler(store, clock);

    @Test
    @DisplayName("서울 기준 보존 기간을 계산하고 배치 단위로 끝까지 정리한다")
    void deletesInBatchesUsingSeoulCutoffs() {
        LocalDateTime completedBefore =
                LocalDateTime.of(2026, 10, 31, 3, 30);
        LocalDateTime failedBefore =
                LocalDateTime.of(2026, 10, 8, 3, 30);

        when(store.deleteBatch(
                ProfilingResultStatus.COMPLETED,
                completedBefore,
                1_000
        )).thenReturn(1_000, 1);

        when(store.deleteBatch(
                ProfilingResultStatus.FAILED,
                failedBefore,
                1_000
        )).thenReturn(0);

        scheduler.cleanupFinishedJobs();

        verify(store, times(2)).deleteBatch(
                ProfilingResultStatus.COMPLETED,
                completedBefore,
                1_000
        );
        verify(store).deleteBatch(
                ProfilingResultStatus.FAILED,
                failedBefore,
                1_000
        );
    }

    @Test
    @DisplayName("성공 이력 정리 실패가 실패 이력 정리를 막지 않는다")
    void isolatesCleanupFailureByResultStatus() {
        LocalDateTime completedBefore =
                LocalDateTime.of(2026, 10, 31, 3, 30);
        LocalDateTime failedBefore =
                LocalDateTime.of(2026, 10, 8, 3, 30);

        when(store.deleteBatch(
                ProfilingResultStatus.COMPLETED,
                completedBefore,
                1_000
        )).thenThrow(new DataAccessResourceFailureException(
                "simulated failure"
        ));

        assertThatCode(scheduler::cleanupFinishedJobs)
                .doesNotThrowAnyException();

        verify(store).deleteBatch(
                ProfilingResultStatus.FAILED,
                failedBefore,
                1_000
        );
    }

    @Test
    @DisplayName("매일 서울 시간 03시 30분에 실행하도록 선언한다")
    void schedulesAt0330Seoul() throws Exception {
        Scheduled scheduled = ProfilingJobCleanupScheduler.class
                .getMethod("cleanupFinishedJobs")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 30 3 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
