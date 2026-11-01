package com.gift.gift.domain.notification.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class NotificationRedisConfig {
    @Bean
    public SimpleAsyncTaskExecutor notificationRedisSubscriptionExecutor() {
        var executor = new SimpleAsyncTaskExecutor("notification-redis-subscribe-");
        executor.setDaemon(true);
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer notificationRedisContainer(
            RedisConnectionFactory connectionFactory, SimpleAsyncTaskExecutor notificationRedisSubscriptionExecutor) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(Runnable::run);
        container.setSubscriptionExecutor(notificationRedisSubscriptionExecutor);
        return container;
    }
}
