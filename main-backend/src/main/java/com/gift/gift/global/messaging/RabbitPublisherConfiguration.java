package com.gift.gift.global.messaging;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.gift.gift.global.outbox.OutboxProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "${app.outbox.enabled:false} or "
                + "${app.payment.recovery.dispatch.enabled:false}"
)
@EnableConfigurationProperties(OutboxProperties.class)
public class RabbitPublisherConfiguration {

    @Bean
    public RabbitTemplate outboxRabbitTemplate(
            @Qualifier("rabbitConnectionFactory")
            CachingConnectionFactory connectionFactory
    ) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setUsePublisherConnection(true);
        template.setMandatory(true);
        return template;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
    static class OutboxRoutingConfiguration {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnExpression("${app.outbox.enabled:false} and ${app.outbox.search-relay-enabled:true}")
        @EnableConfigurationProperties(RabbitRoutingProperties.class)
        static class SearchRoutingConfiguration {
        }

        @Bean(defaultCandidate = false)
        ThreadPoolTaskScheduler outboxTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("outbox-");
            return scheduler;
        }
    }
}
