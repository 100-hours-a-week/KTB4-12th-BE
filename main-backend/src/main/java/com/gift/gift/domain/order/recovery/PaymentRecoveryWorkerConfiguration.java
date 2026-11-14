package com.gift.gift.domain.order.recovery;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    // 컨슈머 설정을 재빌드 없이 비교 측정하기 위해 프로퍼티로 받는다. 승인·취소 복구 큐가 함께 쓰며 기본값 1은 기존 동작과 같다.
    @Value("${app.payment.mq.consumer.recovery.concurrency:1}")
    private int concurrency;

    @Value("${app.payment.mq.consumer.recovery.prefetch:1}")
    private int prefetch;

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
        container.setConcurrentConsumers(concurrency);
        container.setPrefetchCount(prefetch);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(false);
        container.setMessageListener(listener);
        return container;
    }
}
