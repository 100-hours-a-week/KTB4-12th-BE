package com.gift.gift.domain.auth.scheduler;

import java.sql.SQLException;
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

import com.gift.gift.domain.auth.scheduler.AuthenticationDataCleanupScheduler;
import com.gift.gift.domain.auth.service.AuthenticationDataCleanupTransactionService;
import com.gift.gift.domain.auth.service.AuthenticationDataCleanupBatchResult;
import org.springframework.dao.CannotAcquireLockException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        )).thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));

        when(transactionService
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                ))
                .thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));

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
        )).thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));

        when(transactionService
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                ))
                .thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));

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
        )).thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));

        when(transactionService
                .deleteEmailFailureLimitBatch(
                        rateLimitCutoff,
                        NOW,
                        1_000
                ))
                .thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실제 삭제가 0건이어도 후보가 1000건이면 다음 배치를 실행한다")
    void cleanup_repeatsBySelectedCount(boolean email) {
        stubEmptyRateLimits();
        var batch = email
                ? when(transactionService.deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000))
                : when(transactionService.deleteIpRateLimitBatch(NOW.minusHours(24), 1_000));
        batch.thenReturn(new AuthenticationDataCleanupBatchResult(1_000, 0))
                .thenReturn(new AuthenticationDataCleanupBatchResult(7, 7));

        scheduler.cleanupAuthenticationData();

        if (email) {
            verify(transactionService, times(2))
                    .deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000);
        } else {
            verify(transactionService, times(2))
                    .deleteIpRateLimitBatch(NOW.minusHours(24), 1_000);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("1213이면 실패한 배치를 재호출하고 성공 후 종료한다")
    void cleanup_retriesDeadlockThenSucceeds(boolean email) {
        stubEmptyRateLimits();
        var batch = email
                ? when(transactionService.deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000))
                : when(transactionService.deleteIpRateLimitBatch(NOW.minusHours(24), 1_000));
        batch.thenThrow(sqlFailure(1213, "40001"))
                .thenReturn(new AuthenticationDataCleanupBatchResult(1, 1));

        scheduler.cleanupAuthenticationData();

        if (email) {
            verify(transactionService, times(2))
                    .deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000);
        } else {
            verify(transactionService, times(2))
                    .deleteIpRateLimitBatch(NOW.minusHours(24), 1_000);
        }
    }

    @Test
    @DisplayName("1213이 반복되면 총 3회로 제한하고 이메일 정리를 계속한다")
    void cleanup_stopsAfterThreeAttempts() {
        stubEmptyRateLimits();
        when(transactionService.deleteIpRateLimitBatch(NOW.minusHours(24), 1_000))
                .thenThrow(sqlFailure(1213, "40001"));

        assertDoesNotThrow(scheduler::cleanupAuthenticationData);

        verify(transactionService, times(3))
                .deleteIpRateLimitBatch(NOW.minusHours(24), 1_000);
        verify(transactionService)
                .deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000);
    }

    @ParameterizedTest
    @ValueSource(ints = {1205, 1062})
    @DisplayName("SQLState가 40001이어도 오류 번호가 1213이 아니면 재시도하지 않는다")
    void cleanup_doesNotRetryOtherSqlErrors(int errorCode) {
        stubEmptyRateLimits();
        when(transactionService.deleteIpRateLimitBatch(NOW.minusHours(24), 1_000))
                .thenThrow(sqlFailure(errorCode, "40001"));

        scheduler.cleanupAuthenticationData();

        verify(transactionService).deleteIpRateLimitBatch(NOW.minusHours(24), 1_000);
        verify(transactionService)
                .deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000);
    }

    private void stubEmptyRateLimits() {
        org.mockito.Mockito.lenient().when(transactionService
                .deleteIpRateLimitBatch(NOW.minusHours(24), 1_000))
                .thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));
        org.mockito.Mockito.lenient().when(transactionService
                .deleteEmailFailureLimitBatch(NOW.minusHours(24), NOW, 1_000))
                .thenReturn(new AuthenticationDataCleanupBatchResult(0, 0));
    }

    private RuntimeException sqlFailure(int errorCode, String sqlState) {
        return new CannotAcquireLockException(
                "cleanup test", new SQLException("test", sqlState, errorCode)
        );
    }

}
