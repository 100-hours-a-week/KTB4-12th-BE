package com.gift.gift.global.messaging;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@org.springframework.boot.context.properties.EnableConfigurationProperties(RabbitRoutingProperties.class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
public class RabbitPublisherConfiguration {
    @Bean
    public RabbitTemplate outboxRabbitTemplate(
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setUsePublisherConnection(true);
        template.setMandatory(true);
        return template;
    }

    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler outboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("outbox-");
        return scheduler;
    }
}
