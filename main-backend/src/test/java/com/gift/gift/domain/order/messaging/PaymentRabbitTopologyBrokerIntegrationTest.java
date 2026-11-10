package com.gift.gift.domain.order.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.gift.gift.global.messaging.RabbitConnectionConfiguration;

import static com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentRabbitTopologyBrokerIntegrationTest {

    private static final List<String> WORK_QUEUES = List.of(
            RECEIPT_QUEUE,
            ROULETTE_QUEUE,
            COUPON_RESTORE_QUEUE,
            APPROVAL_RECOVERY_QUEUE,
            CANCELLATION_RECOVERY_QUEUE
    );

    private static final List<String> DLQS = List.of(
            RECEIPT_DLQ,
            ROULETTE_DLQ,
            COUPON_RESTORE_DLQ,
            APPROVAL_RECOVERY_DLQ,
            CANCELLATION_RECOVERY_DLQ
    );

    @Test
    @DisplayName("RabbitMQ 4.4에서 이벤트와 작업별 DLX 라우팅을 격리하고 부분 바인딩의 Confirm 동작을 확인한다")
    void declaresAndRoutesPaymentTopology() {
        try (var broker = new GenericContainer<>("rabbitmq:4.4.0-management")
                .withEnv("RABBITMQ_DEFAULT_USER", "test")
                .withEnv("RABBITMQ_DEFAULT_PASS", "test-password")
                .withEnv("RABBITMQ_DEFAULT_VHOST", "gift")
                .withExposedPorts(5672, 15672)
                .waitingFor(Wait.forHttp("/api/overview")
                        .forPort(15672)
                        .withBasicCredentials("test", "test-password")
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            broker.start();

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                    .withUserConfiguration(
                            RabbitConnectionConfiguration.class,
                            PaymentRabbitTopologyConfiguration.class
                    )
                    .withPropertyValues(
                            "app.payment.mq.topology.enabled=true",
                            "app.payment.mq.topology.initial-group-size=1",
                            "spring.rabbitmq.host=" + broker.getHost(),
                            "spring.rabbitmq.port=" + broker.getMappedPort(5672),
                            "spring.rabbitmq.username=test",
                            "spring.rabbitmq.password=test-password",
                            "spring.rabbitmq.virtual-host=gift",
                            "spring.rabbitmq.dynamic=false",
                            "spring.rabbitmq.publisher-confirm-type=correlated",
                            "spring.rabbitmq.publisher-returns=true"
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        try {
                            RabbitAdmin admin = context.getBean("paymentRabbitAdmin", RabbitAdmin.class);
                            admin.initialize();

                            var queues = broker.execInContainer(
                                    "rabbitmqctl", "list_queues", "-p", "gift", "name", "type", "durable"
                            );
                            assertThat(queues.getExitCode()).isZero();
                            for (String queue : WORK_QUEUES) {
                                assertThat(queues.getStdout()).contains(queue + "\tquorum\ttrue");
                            }
                            for (String queue : DLQS) {
                                assertThat(queues.getStdout()).contains(queue + "\tquorum\ttrue");
                            }

                            RabbitTemplate template = new RabbitTemplate(
                                    context.getBean(CachingConnectionFactory.class)
                            );
                            template.setMandatory(true);

                            assertRouting(
                                    template, EVENTS_EXCHANGE, ORDER_COMPLETED_ROUTING_KEY,
                                    WORK_QUEUES, Set.of(RECEIPT_QUEUE, ROULETTE_QUEUE)
                            );
                            assertRouting(
                                    template, EVENTS_EXCHANGE, ORDER_CANCELED_ROUTING_KEY,
                                    WORK_QUEUES, Set.of(RECEIPT_QUEUE, COUPON_RESTORE_QUEUE)
                            );
                            assertRouting(
                                    template, EVENTS_EXCHANGE, APPROVAL_RECOVERY_ROUTING_KEY,
                                    WORK_QUEUES, Set.of(APPROVAL_RECOVERY_QUEUE)
                            );
                            assertRouting(
                                    template, EVENTS_EXCHANGE, CANCELLATION_RECOVERY_ROUTING_KEY,
                                    WORK_QUEUES, Set.of(CANCELLATION_RECOVERY_QUEUE)
                            );

                            List<DeadLetterRoute> deadLetterRoutes = List.of(
                                    new DeadLetterRoute(RECEIPT_DLX, ORDER_COMPLETED_ROUTING_KEY, RECEIPT_DLQ),
                                    new DeadLetterRoute(RECEIPT_DLX, ORDER_CANCELED_ROUTING_KEY, RECEIPT_DLQ),
                                    new DeadLetterRoute(RECEIPT_DLX, RECEIPT_QUEUE, RECEIPT_DLQ),
                                    new DeadLetterRoute(ROULETTE_DLX, ORDER_COMPLETED_ROUTING_KEY, ROULETTE_DLQ),
                                    new DeadLetterRoute(ROULETTE_DLX, ROULETTE_QUEUE, ROULETTE_DLQ),
                                    new DeadLetterRoute(COUPON_RESTORE_DLX, ORDER_CANCELED_ROUTING_KEY, COUPON_RESTORE_DLQ),
                                    new DeadLetterRoute(COUPON_RESTORE_DLX, COUPON_RESTORE_QUEUE, COUPON_RESTORE_DLQ),
                                    new DeadLetterRoute(APPROVAL_RECOVERY_DLX, APPROVAL_RECOVERY_ROUTING_KEY, APPROVAL_RECOVERY_DLQ),
                                    new DeadLetterRoute(APPROVAL_RECOVERY_DLX, APPROVAL_RECOVERY_QUEUE, APPROVAL_RECOVERY_DLQ),
                                    new DeadLetterRoute(CANCELLATION_RECOVERY_DLX, CANCELLATION_RECOVERY_ROUTING_KEY, CANCELLATION_RECOVERY_DLQ),
                                    new DeadLetterRoute(CANCELLATION_RECOVERY_DLX, CANCELLATION_RECOVERY_QUEUE, CANCELLATION_RECOVERY_DLQ)
                            );

                            for (DeadLetterRoute route : deadLetterRoutes) {
                                assertRouting(
                                        template, route.exchange(), route.routingKey(),
                                        DLQS, Set.of(route.dlq())
                                );
                            }

                            admin.removeBinding(new Binding(
                                    ROULETTE_QUEUE,
                                    Binding.DestinationType.QUEUE,
                                    EVENTS_EXCHANGE,
                                    ORDER_COMPLETED_ROUTING_KEY,
                                    null
                            ));

                            // 영수증 큐에만 연결이 남아 있으므로 Confirm은 성공하고 Return은 없어야 한다.
                            assertRouting(
                                    template, EVENTS_EXCHANGE, ORDER_COMPLETED_ROUTING_KEY,
                                    WORK_QUEUES, Set.of(RECEIPT_QUEUE)
                            );
                        } catch (Exception exception) {
                            throw new AssertionError("RabbitMQ 토폴로지 검증에 실패했습니다.", exception);
                        }
                    });
        }
    }

    private static void assertRouting(
            RabbitTemplate template,
            String exchange,
            String routingKey,
            List<String> candidateQueues,
            Set<String> expectedQueues
    ) throws Exception {
        String body = UUID.randomUUID().toString();
        CorrelationData confirm = new CorrelationData(body);

        template.convertAndSend(exchange, routingKey, body, confirm);

        assertThat(confirm.getFuture().get(10, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(confirm.getReturned()).isNull();

        for (String queue : candidateQueues) {
            Message message = template.receive(queue, expectedQueues.contains(queue) ? 5000 : 100);

            if (expectedQueues.contains(queue)) {
                assertThat(message).isNotNull();
                assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(body);
            } else {
                assertThat(message).isNull();
            }
        }
    }

    private record DeadLetterRoute(String exchange, String routingKey, String dlq) {
    }
}
