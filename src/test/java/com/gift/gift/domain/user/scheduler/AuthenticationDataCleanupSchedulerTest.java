package com.gift.gift.domain.user.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.user.service.AuthenticationDataCleanupTransactionService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationDataCleanupSchedulerTest {

    private static final ZoneId ZONE_ID =
            ZoneId.of("Asia/Seoul");

    private static final Instant NOW_INSTANT =
            Instant.parse("2026-09-22T18:00:00Z");

    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(
                    NOW_INSTANT,
                    ZONE_ID
            );

    @Mock
    private AuthenticationDataCleanupTransactionService
            transactionService;

    private AuthenticationDataCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                NOW_INSTANT,
                ZONE_ID
        );

        scheduler = new AuthenticationDataCleanupScheduler(
                transactionService,
                clock
        );
    }

    @Test
    @DisplayName("1,000건이 삭제되면 다음 독립 배치를 반복한다")
    void cleanupAuthenticationData_repeatsFullBatches() {
        LocalDateTime sessionCutoff =
                NOW.minusDays(30);

        LocalDateTime rateLimitCutoff =
                NOW.minusHours(24);

        when(transactionService.deleteSessionBatch(
                sessionCutoff,
                1_000
        ))
                .thenReturn(1_000)
                .thenReturn(1_000)
                .thenReturn(37);

        when(transactionService.deleteIpRateLimitBatch(
                rateLimitCutoff,
                1_000
        )).thenReturn(0);

        when(transactionService
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                ))
                .thenReturn(0);

        scheduler.cleanupAuthenticationData();

        verify(
                transactionService,
                times(3)
        ).deleteSessionBatch(
                sessionCutoff,
                1_000
        );

        verify(transactionService)
                .deleteIpRateLimitBatch(
                        rateLimitCutoff,
                        1_000
                );

        verify(transactionService)
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                );
    }

    @Test
    @DisplayName("세션 정리에 실패해도 IP와 이메일 제한 정리를 계속한다")
    void cleanupAuthenticationData_continuesOtherTargetsAfterFailure() {
        LocalDateTime sessionCutoff =
                NOW.minusDays(30);

        LocalDateTime rateLimitCutoff =
                NOW.minusHours(24);

        when(transactionService.deleteSessionBatch(
                sessionCutoff,
                1_000
        )).thenThrow(
                new IllegalStateException(
                        "session cleanup failed"
                )
        );

        when(transactionService.deleteIpRateLimitBatch(
                rateLimitCutoff,
                1_000
        )).thenReturn(0);

        when(transactionService
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                ))
                .thenReturn(0);

        assertDoesNotThrow(
                scheduler::cleanupAuthenticationData
        );

        verify(transactionService)
                .deleteIpRateLimitBatch(
                        rateLimitCutoff,
                        1_000
                );

        verify(transactionService)
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                );
    }

    @Test
    @DisplayName("일부 배치 커밋 후 실패해도 다른 대상 정리를 계속한다")
    void cleanupAuthenticationData_preservesCompletedBatchesOnLaterFailure() {
        LocalDateTime sessionCutoff =
                NOW.minusDays(30);

        LocalDateTime rateLimitCutoff =
                NOW.minusHours(24);

        when(transactionService.deleteSessionBatch(
                sessionCutoff,
                1_000
        ))
                .thenReturn(1_000)
                .thenThrow(
                        new IllegalStateException(
                                "next batch failed"
                        )
                );

        when(transactionService.deleteIpRateLimitBatch(
                rateLimitCutoff,
                1_000
        )).thenReturn(0);

        when(transactionService
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                ))
                .thenReturn(0);

        assertDoesNotThrow(
                scheduler::cleanupAuthenticationData
        );

        verify(
                transactionService,
                times(2)
        ).deleteSessionBatch(
                sessionCutoff,
                1_000
        );

        verify(transactionService)
                .deleteIpRateLimitBatch(
                        rateLimitCutoff,
                        1_000
                );

        verify(transactionService)
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                );
    }
}
