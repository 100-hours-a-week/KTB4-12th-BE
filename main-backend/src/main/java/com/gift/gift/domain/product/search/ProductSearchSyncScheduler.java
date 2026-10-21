package com.gift.gift.domain.product.search;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Slf4j
@RequiredArgsConstructor
public class ProductSearchSyncScheduler
        implements InitializingBean, DisposableBean {

    private final ProductSearchSyncService service;
    private final ProductSearchSyncProperties settings;

    private final ThreadPoolTaskScheduler scheduler =
            new ThreadPoolTaskScheduler();

    @Override
    public void afterPropertiesSet() {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("product-search-sync-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.initialize();

        scheduler.scheduleWithFixedDelay(
                this::poll,
                Duration.ofMillis(settings.pollIntervalMs())
        );
    }

    public void poll() {
        try {
            service.syncOnce();
        } catch (Exception exception) {
            // DB 장애 등으로 상태 기록도 실패한 작업은 임대 만료 후 회수한다.
            log.error(
                    "상품 검색 동기화 실행 실패: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
