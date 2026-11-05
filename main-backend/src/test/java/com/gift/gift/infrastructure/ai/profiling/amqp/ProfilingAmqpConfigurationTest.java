package com.gift.gift.infrastructure.ai.profiling.amqp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilingAmqpConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ProfilingAmqpConfiguration.class
                    );

    @Test
    @DisplayName("프로파일링 AMQP가 비활성화되면 RabbitMQ 관련 빈을 생성하지 않는다")
    void disabledDoesNotCreateRabbitInfrastructure() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RabbitTemplate.class);
            assertThat(context).doesNotHaveBean(RabbitAdmin.class);
            assertThat(context)
                    .doesNotHaveBean(CachingConnectionFactory.class);
        });
    }

    @Test
    @DisplayName("프로파일링 AMQP가 활성화되면 Quorum 큐와 발행 전용 연결을 설정한다")
    void enabledConfiguresQuorumAndPublisherConnection() {
        runner.withPropertyValues(
                "app.ai-profile.amqp.enabled=true",
                "app.ai-profile.amqp.username=profiling",
                "app.ai-profile.amqp.password=test-password"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            CachingConnectionFactory factory = context.getBean(
                    "profilingConnectionFactory",
                    CachingConnectionFactory.class
            );

            assertThat(factory.getRabbitConnectionFactory()
                    .getConnectionTimeout()).isEqualTo(3000);

            assertThat(factory.getRabbitConnectionFactory()
                    .getRequestedHeartbeat()).isEqualTo(20);

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
