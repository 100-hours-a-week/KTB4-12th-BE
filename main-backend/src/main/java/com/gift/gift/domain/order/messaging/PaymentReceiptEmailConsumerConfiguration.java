package com.gift.gift.domain.order.messaging;

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

import com.gift.gift.domain.notification.event.handler.PaymentReceiptEmailEventHandler;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "${app.payment.mq.consumer.receipt.enabled:false} and ${app.payment.mq.topology.enabled:false}"
)
public class PaymentReceiptEmailConsumerConfiguration {

    @Bean
    public PaymentReceiptEmailConsumer paymentReceiptEmailConsumer(
            ObjectMapper objectMapper,
            PaymentReceiptDeliveryTransactionService transactionService,
            PaymentReceiptEmailEventHandler emailHandler
    ) {
        return new PaymentReceiptEmailConsumer(
                objectMapper,
                transactionService,
                emailHandler
        );
    }

    @Bean
    public SimpleMessageListenerContainer paymentReceiptEmailListenerContainer(
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory,
            PaymentReceiptEmailConsumer consumer
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(PaymentRabbitTopologyConfiguration.RECEIPT_QUEUE);
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
    public ApplicationRunner startPaymentReceiptEmailConsumer(
            @Qualifier("paymentRabbitAdmin") RabbitAdmin paymentRabbitAdmin,
            @Qualifier("paymentReceiptEmailListenerContainer") SimpleMessageListenerContainer container
    ) {
        return args -> {
            paymentRabbitAdmin.initialize();
            container.start();
        };
    }
}
