package com.gift.gift.domain.product.scheduler;

import java.time.Duration;
import java.time.Instant;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.product.service.ProductViewSyncService;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProductViewSyncScheduler {

    private static final String LOCK_NAME = "syncProductViews";

    private final ProductViewSyncService productViewSyncService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Duration interval;

    public ProductViewSyncScheduler(
            ProductViewSyncService productViewSyncService,
            LockProvider lockProvider,
            @Value("${app.product-view.sync-interval:PT10M}") Duration interval
    ) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("조회수 반영 주기는 0보다 커야 합니다.");
        }

        this.productViewSyncService = productViewSyncService;
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.interval = interval;
    }

    @Scheduled(fixedDelayString = "${app.product-view.sync-interval:PT10M}")
    public void sync() {
        lockingTaskExecutor.executeWithLock(
                (Runnable) this::run,
                new LockConfiguration(Instant.now(), LOCK_NAME, interval.multipliedBy(2), interval)
        );
    }

    private void run() {
        try {
            int syncedCount = productViewSyncService.sync();

            if (syncedCount > 0) {
                log.info("상품 조회수를 DB에 반영했습니다. productCount={}", syncedCount);
            }
        } catch (RuntimeException exception) {
            log.error("상품 조회수 DB 반영에 실패했습니다.", exception);
        }
    }
}
