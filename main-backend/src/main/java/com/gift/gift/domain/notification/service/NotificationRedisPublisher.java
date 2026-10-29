package com.gift.gift.domain.notification.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRedisPublisher {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    static String channel(Long recipientId) {
        return "notifications:user:" + recipientId;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(NotificationCreatedEvent event) {
        try {
            redis.convertAndSend(channel(event.recipientId()), mapper.writeValueAsString(event));
            metrics.counter("notification.redis.publish", "result", "success").increment();
        } catch (RuntimeException failure) {
            metrics.counter("notification.redis.publish", "result", "failure").increment();
            log.warn("알림 Redis 발행 실패: notificationId={}, errorType={}",
                    event.notification().notificationId(), failure.getClass().getName());
        }
    }
}
