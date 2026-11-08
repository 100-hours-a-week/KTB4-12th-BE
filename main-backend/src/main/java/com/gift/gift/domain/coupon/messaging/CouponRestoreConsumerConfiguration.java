package com.gift.gift.domain.coupon.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.coupon.service.CouponRestoreTransactionService;
import com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "${app.payment.mq.enabled:false} and ${app.payment.mq.topology.enabled:false}"
)
public class CouponRestoreConsumerConfiguration {

    @Bean
    public CouponRestoreConsumer couponRestoreConsumer(
            ObjectMapper objectMapper,
            CouponRestoreTransactionService transactionService
    ) {
        return new CouponRestoreConsumer(objectMapper, transactionService);
    }

    @Bean
    public SimpleMessageListenerContainer couponRestoreListenerContainer(
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory,
            CouponRestoreConsumer consumer
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(PaymentRabbitTopologyConfiguration.COUPON_RESTORE_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1);
        container.setPrefetchCount(1);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(false);
        container.setMessageListener(consumer);
        return container;
    }

    @Bean
    public ApplicationRunner startCouponRestoreConsumer(
            @Qualifier("paymentRabbitAdmin") RabbitAdmin paymentRabbitAdmin,
            @Qualifier("couponRestoreListenerContainer") SimpleMessageListenerContainer container
    ) {
        return args -> {
            paymentRabbitAdmin.initialize();
            container.start();
        };
    }
}
