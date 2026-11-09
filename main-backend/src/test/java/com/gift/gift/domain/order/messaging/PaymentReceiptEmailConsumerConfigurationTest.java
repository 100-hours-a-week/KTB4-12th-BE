package com.gift.gift.domain.order.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.notification.event.handler.PaymentReceiptEmailEventHandler;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentReceiptEmailConsumerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentReceiptEmailConsumerConfiguration.class)
            .withBean(
                    "rabbitConnectionFactory",
                    CachingConnectionFactory.class,
                    () -> mock(CachingConnectionFactory.class)
            )
            .withBean(
                    "paymentRabbitAdmin",
                    RabbitAdmin.class,
                    () -> mock(RabbitAdmin.class)
            )
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(
                    PaymentReceiptDeliveryTransactionService.class,
                    () -> mock(PaymentReceiptDeliveryTransactionService.class)
            )
            .withBean(
                    PaymentReceiptEmailEventHandler.class,
                    () -> mock(PaymentReceiptEmailEventHandler.class)
            );

    @Test
    @DisplayName("두 설정이 활성화되면 영수증 큐에 수동 ACK Listener를 연결한다")
    void consumer_usesManualAcknowledgementForReceiptQueue() {
        contextRunner
                .withPropertyValues(
                        "app.payment.mq.consumer.receipt.enabled=true",
                        "app.payment.mq.topology.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    SimpleMessageListenerContainer container =
                            context.getBean("paymentReceiptEmailListenerContainer",
                                    SimpleMessageListenerContainer.class);

                    assertThat(container.getQueueNames())
                            .containsExactly(PaymentRabbitTopologyConfiguration.RECEIPT_QUEUE);
                    assertThat(container.getAcknowledgeMode())
                            .isEqualTo(AcknowledgeMode.MANUAL);
                    assertThat(container.isAutoStartup()).isFalse();
                });
    }
}
