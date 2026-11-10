package com.gift.gift.domain.order.recovery;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "${app.payment.mq.topology.enabled:false} and "
                + "${app.payment.recovery.dispatch.enabled:false}"
)
public class PaymentRecoveryWorkerConfiguration {

    @Bean
    public SimpleMessageListenerContainer approvalRecoveryContainer(
            @Qualifier("rabbitConnectionFactory")
            CachingConnectionFactory connectionFactory,
            PaymentRecoveryWorker worker
    ) {
        return createContainer(
                connectionFactory,
                PaymentRabbitTopologyConfiguration.APPROVAL_RECOVERY_QUEUE,
                (message, channel) -> worker.consume(
                        message, channel, PaymentRecoveryTaskType.APPROVAL
                )
        );
    }

    @Bean
    public SimpleMessageListenerContainer cancellationRecoveryContainer(
            @Qualifier("rabbitConnectionFactory")
            CachingConnectionFactory connectionFactory,
            PaymentRecoveryWorker worker
    ) {
        return createContainer(
                connectionFactory,
                PaymentRabbitTopologyConfiguration.CANCELLATION_RECOVERY_QUEUE,
                (message, channel) -> worker.consume(
                        message, channel, PaymentRecoveryTaskType.CANCELLATION
                )
        );
    }

    @Bean
    public ApplicationRunner startPaymentRecoveryWorkers(
            @Qualifier("paymentRabbitAdmin") RabbitAdmin rabbitAdmin,
            @Qualifier("approvalRecoveryContainer")
            SimpleMessageListenerContainer approvalContainer,
            @Qualifier("cancellationRecoveryContainer")
            SimpleMessageListenerContainer cancellationContainer
    ) {
        return args -> {
            rabbitAdmin.initialize();
            approvalContainer.start();
            cancellationContainer.start();
        };
    }

    private SimpleMessageListenerContainer createContainer(
            CachingConnectionFactory connectionFactory,
            String queueName,
            ChannelAwareMessageListener listener
    ) {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1);
        container.setPrefetchCount(1);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(false);
        container.setMessageListener(listener);
        return container;
    }
}
