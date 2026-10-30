package com.gift.gift.domain.notification.service;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationSseHeartbeatScheduler implements InitializingBean, DisposableBean {
    private final NotificationSseHub hub;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @Override
    public void afterPropertiesSet() {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-sse-heartbeat-");
        scheduler.initialize();
        scheduler.scheduleAtFixedRate(hub::sendHeartbeats, Duration.ofSeconds(15));
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
