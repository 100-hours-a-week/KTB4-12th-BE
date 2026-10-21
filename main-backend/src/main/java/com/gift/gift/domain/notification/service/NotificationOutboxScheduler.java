package com.gift.gift.domain.notification.service;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxScheduler implements InitializingBean, DisposableBean {
    private final NotificationOutboxDispatcher dispatcher;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @Value("${app.notification.outbox.poll-delay-ms:200}")
    private long pollDelayMillis = 200;

    @Override
    public void afterPropertiesSet() {
        if (pollDelayMillis <= 0) {
            throw new IllegalArgumentException("알림 Outbox 폴링 지연 시간은 양수여야 합니다.");
        }
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-outbox-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::poll, Duration.ofMillis(pollDelayMillis));
    }

    public void poll() {
        try {
            dispatcher.dispatch();
        } catch (RuntimeException failure) {
            log.error("알림 Outbox 조회 실패: errorType={}", failure.getClass().getName());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
