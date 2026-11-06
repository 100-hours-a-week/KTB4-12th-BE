package com.gift.gift.infrastructure.ai.profiling.amqp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;

import com.gift.gift.global.messaging.RabbitConnectionConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilingAmqpConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                    .withPropertyValues("spring.rabbitmq.dynamic=false",
                            "spring.rabbitmq.username=profiling", "spring.rabbitmq.password=test-password",
                            "spring.rabbitmq.connection-timeout=3s", "spring.rabbitmq.requested-heartbeat=20s",
                            "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true")
                    .withUserConfiguration(
                            RabbitConnectionConfiguration.class,
                            ProfilingAmqpConfiguration.class,
                            ProfilingPublisherState.class
                    );

    @Test
    @DisplayName("프로파일링 AMQP가 비활성화되면 도메인 빈과 연결을 생성하지 않는다")
    void disabledDoesNotCreateRabbitInfrastructure() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("profilingRabbitTemplate");
            assertThat(context).doesNotHaveBean(RabbitAdmin.class);
            assertThat(context).doesNotHaveBean("profilingConnectionFactory");
            assertThat(context).doesNotHaveBean("rabbitHealthIndicator");
        });
    }

    @Test
    @DisplayName("프로파일링 AMQP가 활성화되면 Quorum 큐와 발행 전용 연결을 설정한다")
    void enabledConfiguresQuorumAndPublisherConnection() {
        runner.withPropertyValues(
                "app.ai-profile.amqp.enabled=true"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            CachingConnectionFactory factory = context.getBean(
                    "rabbitConnectionFactory",
                    CachingConnectionFactory.class
            );

            assertThat(factory.getRabbitConnectionFactory()
                    .getConnectionTimeout()).isEqualTo(3000);

            assertThat(factory.getRabbitConnectionFactory()
                    .getRequestedHeartbeat()).isEqualTo(20);

            assertThat(factory.getChannelCacheSize()).isEqualTo(25);
            assertThat(factory.getCloseTimeout()).isEqualTo(3000);
            var client = factory.getRabbitConnectionFactory();
            assertThat(client.getHandshakeTimeout()).isEqualTo(3000);
            assertThat(client.getChannelRpcTimeout()).isEqualTo(3000);
            assertThat(client.getShutdownTimeout()).isEqualTo(3000);
            assertThat(client.getNioParams().getWriteQueueCapacity()).isEqualTo(64);
            assertThat(client.getNioParams().getWriteEnqueuingTimeoutInMs()).isEqualTo(3000);

            RabbitTemplate template = context.getBean(
                    "profilingRabbitTemplate",
                    RabbitTemplate.class
            );

            assertThat(template.isUsePublisherConnection()).isTrue();

            Declarables topology =
                    context.getBean(Declarables.class);

            assertThat(topology.getDeclarablesByType(Queue.class))
                    .hasSize(2)
                    .allSatisfy(queue -> {
                        assertThat(queue.isDurable()).isTrue();
                        assertThat(queue.isExclusive()).isFalse();
                        assertThat(queue.isAutoDelete()).isFalse();

                        assertThat(queue.getArguments())
                                .containsEntry("x-queue-type", "quorum")
                                .containsEntry(
                                        "x-quorum-initial-group-size",
                                        1
                                );
                    });
        });
    }
}
