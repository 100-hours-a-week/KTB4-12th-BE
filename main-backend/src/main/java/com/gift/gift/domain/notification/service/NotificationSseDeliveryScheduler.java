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

import com.gift.gift.domain.notification.dto.response.NotificationListItem;

@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NotificationSseDeliveryScheduler implements InitializingBean, DisposableBean {
    private final NotificationSseDeliveryService deliveries;
    private final NotificationSseHub hub;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @Value("${app.notification.sse.poll-delay-ms:200}")
    private long pollDelayMillis = 200;
    @Value("${app.notification.sse.batch-size:100}")
    private int batchSize = 100;

    @Override
    public void afterPropertiesSet() {
        if (pollDelayMillis <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("SSE 폴링 대기 시간과 배치 크기는 양수여야 합니다.");
        }
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("notification-sse-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::poll, Duration.ofMillis(pollDelayMillis));
        scheduler.scheduleAtFixedRate(hub::sendHeartbeats, Duration.ofSeconds(15));
    }

    public void poll() {
        try {
            if (!hub.hasConnections()) return;
            for (var delivery : deliveries.findDue(hub.nodeId(), batchSize)) {
                try {
                    if (deliveries.completeBeforeSend(delivery.id(), hub.nodeId())) {
                        hub.send(delivery.connectionId(), delivery.id(),
                                NotificationListItem.from(delivery.notification()));
                    }
                } catch (RuntimeException failure) {
                    log.warn("SSE 전달 처리 실패: errorType={}", failure.getClass().getName());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("SSE 전달 작업 조회 실패: errorType={}", failure.getClass().getName());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
        hub.closeAll();
    }
}
