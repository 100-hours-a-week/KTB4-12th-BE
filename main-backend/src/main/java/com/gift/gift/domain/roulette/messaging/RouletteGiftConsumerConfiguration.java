package com.gift.gift.domain.roulette.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration;
import com.gift.gift.domain.roulette.service.RouletteGrantTransactionService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "${app.payment.mq.enabled:false} and ${app.payment.mq.topology.enabled:false}"
)
public class RouletteGiftConsumerConfiguration {

    @Bean
    public RouletteGiftConsumer rouletteGiftConsumer(
            ObjectMapper objectMapper,
            RouletteGrantTransactionService transactionService
    ) {
        return new RouletteGiftConsumer(objectMapper, transactionService);
    }

    @Bean
    public SimpleMessageListenerContainer rouletteGiftListenerContainer(
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory,
            RouletteGiftConsumer consumer,
            @Value("${app.payment.mq.consumer.roulette.concurrency:1}") int concurrency,
            @Value("${app.payment.mq.consumer.roulette.prefetch:1}") int prefetch
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(PaymentRabbitTopologyConfiguration.ROULETTE_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // 컨슈머 설정을 재빌드 없이 비교 측정하기 위해 프로퍼티로 받는다. 기본값 1은 기존 동작과 같다.
        container.setConcurrentConsumers(concurrency);
        container.setPrefetchCount(prefetch);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(false);
        container.setMessageListener(consumer);
        return container;
    }

    @Bean
    public ApplicationRunner startRouletteGiftConsumer(
            @Qualifier("paymentRabbitAdmin") RabbitAdmin paymentRabbitAdmin,
            @Qualifier("rouletteGiftListenerContainer") SimpleMessageListenerContainer container
    ) {
        return args -> {
            paymentRabbitAdmin.initialize();
            container.start();
        };
    }
}
