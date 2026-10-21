package com.gift.gift.domain.auth.scheduler;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.auth.service.AuthenticationDataCleanupBatchResult;
import com.gift.gift.domain.auth.service.AuthenticationDataCleanupTransactionService;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationDataCleanupScheduler {

    private static final int BATCH_SIZE = 1_000;

    private static final int SESSION_RETENTION_DAYS = 30;

    private static final int RATE_LIMIT_INACTIVE_HOURS = 24;

    private static final int MAX_BATCH_ATTEMPTS = 3;

    private final AuthenticationDataCleanupTransactionService
            transactionService;

    private final Clock clock;

    @Scheduled(
            cron = "0 0 4 * * *",
            zone = "Asia/Seoul"
    )
    @SchedulerLock(
            name = "cleanupAuthenticationData",
            lockAtMostFor = "${app.auth-cleanup.lock-at-most-for:PT1M}",
            lockAtLeastFor = "${app.auth-cleanup.lock-at-least-for:PT20S}"
    )
    public void cleanupAuthenticationData() {
        LocalDateTime now = LocalDateTime.now(clock);

        LocalDateTime sessionInactiveBefore =
                now.minusDays(SESSION_RETENTION_DAYS);

        LocalDateTime rateLimitInactiveBefore =
                now.minusHours(RATE_LIMIT_INACTIVE_HOURS);

        cleanupTarget(
                "userSessions",
                now,
                () -> transactionService.deleteSessionBatch(
                        sessionInactiveBefore,
                        BATCH_SIZE
                )
        );

        cleanupRateLimitTarget(
                "loginIpRateLimits",
                now,
                () -> transactionService.deleteIpRateLimitBatch(
                        rateLimitInactiveBefore,
                        BATCH_SIZE
                )
        );

        cleanupRateLimitTarget(
                "loginEmailFailureLimits",
                now,
                () -> transactionService.deleteEmailFailureLimitBatch(
                        rateLimitInactiveBefore,
                        now,
                        BATCH_SIZE
                )
        );
    }

    private void cleanupTarget(
            String target,
            LocalDateTime executedAt,
            IntSupplier deletion
    ) {
        int deletedCount = 0;
        int batchCount = 0;

        try {
            while (true) {
                int deleted = deletion.getAsInt();

                if (deleted == 0) {
                    break;
                }

                deletedCount += deleted;
                batchCount++;

                if (deleted < BATCH_SIZE) {
                    break;
                }
            }

            log.info(
                    "인증 데이터 정리를 완료했습니다. "
                            + "target={}, deletedCount={}, "
                            + "batchCount={}, executedAt={}",
                    target,
                    deletedCount,
                    batchCount,
                    executedAt
            );
        } catch (RuntimeException exception) {
            log.error(
                    "인증 데이터 정리가 일부 실패했습니다. "
                            + "target={}, committedDeletedCount={}, "
                            + "committedBatchCount={}, executedAt={}",
                    target,
                    deletedCount,
                    batchCount,
                    executedAt,
                    exception
            );
        }
    }

    private void cleanupRateLimitTarget(
            String target,
            LocalDateTime executedAt,
            Supplier<AuthenticationDataCleanupBatchResult> deletion
    ) {
        long deletedCount = 0;
        int batchCount = 0;

        try {
            while (true) {
                AuthenticationDataCleanupBatchResult result = executeBatchWithRetry(target, deletion);

                if (result.selectedCount() == 0) {
                    break;
                }

                deletedCount += result.deletedCount();
                batchCount++;

                if (result.selectedCount() < BATCH_SIZE) {
                    break;
                }
            }

            log.info(
                    "인증 데이터 정리를 완료했습니다. "
                            + "target={}, deletedCount={}, "
                            + "batchCount={}, executedAt={}",
                    target,
                    deletedCount,
                    batchCount,
                    executedAt
            );
        } catch (RuntimeException exception) {
            log.error(
                    "인증 데이터 정리가 일부 실패했습니다. "
                            + "target={}, committedDeletedCount={}, "
                            + "committedBatchCount={}, executedAt={}",
                    target,
                    deletedCount,
                    batchCount,
                    executedAt,
                    exception
            );
        }
    }

    private AuthenticationDataCleanupBatchResult executeBatchWithRetry(
            String target,
            Supplier<AuthenticationDataCleanupBatchResult> deletion
    ) {
        for (int attempt = 1; ; attempt++) {
            try {
                return deletion.get();
            } catch (RuntimeException exception) {
                if (!isMySqlDeadlock(exception)
                        || attempt >= MAX_BATCH_ATTEMPTS) {
                    throw exception;
                }

                long delayMillis = ThreadLocalRandom.current()
                        .nextLong(50L, 151L) * attempt;

                log.warn(
                        "인증 데이터 정리 배치를 데드락으로 재시도합니다. "
                                + "target={}, failedAttempt={}, "
                                + "maxAttempts={}, delayMillis={}",
                        target,
                        attempt,
                        MAX_BATCH_ATTEMPTS,
                        delayMillis
                );

                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();

                    throw new IllegalStateException(
                            "인증 데이터 정리 배치 재시도가 중단되었습니다.",
                            interruptedException
                    );
                }
            }
        }
    }

    private boolean isMySqlDeadlock(Throwable exception) {
        for (Throwable cause = exception;
             cause != null;
             cause = cause.getCause()) {

            if (!(cause instanceof SQLException sqlException)) {
                continue;
            }

            for (SQLException current = sqlException;
                 current != null;
                 current = current.getNextException()) {

                if (current.getErrorCode() == 1213) {
                    return true;
                }
            }
        }

        return false;
    }
}
