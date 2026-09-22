package com.gift.gift.domain.user.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.user.service.AuthenticationDataCleanupTransactionService;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationDataCleanupScheduler {

    private static final int BATCH_SIZE = 1_000;

    private static final int SESSION_RETENTION_DAYS = 30;

    private static final int RATE_LIMIT_INACTIVE_HOURS = 24;

    private final AuthenticationDataCleanupTransactionService
            transactionService;

    private final Clock clock;

    @Scheduled(
            cron = "0 0 3 * * *",
            zone = "Asia/Seoul"
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

        cleanupTarget(
                "loginIpRateLimits",
                now,
                () -> transactionService.deleteIpRateLimitBatch(
                        rateLimitInactiveBefore,
                        BATCH_SIZE
                )
        );

        cleanupTarget(
                "loginEmailFailureLimits",
                now,
                () -> transactionService
                        .deleteEmailFailureLimitBatch(
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
                    "Authentication data cleanup completed. "
                            + "target={}, deletedCount={}, "
                            + "batchCount={}, executedAt={}",
                    target,
                    deletedCount,
                    batchCount,
                    executedAt
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Authentication data cleanup partially failed. "
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
}
