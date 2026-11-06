package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.health.RabbitHealthIndicator;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;

import com.gift.gift.global.messaging.RabbitConnectionConfiguration;
import com.gift.gift.global.messaging.RabbitPublisherConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;


import static org.assertj.core.api.Assertions.assertThat;

class ProfilingAmqpBrokerIntegrationTest {
    @Test
    void declaresQuorumAndConfirmsReturnsAndReconnects() {
        try (var broker = new GenericContainer<>("rabbitmq:4.4.0-management")
                .withEnv("RABBITMQ_DEFAULT_USER", "profiling")
                .withEnv("RABBITMQ_DEFAULT_PASS", "test-password")
                .withEnv("RABBITMQ_DEFAULT_VHOST", "profiling")
                .withExposedPorts(5672, 15672)
                .waitingFor(Wait.forHttp("/api/overview").forPort(15672)
                        .withBasicCredentials("profiling", "test-password")
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            broker.start();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                    .withUserConfiguration(RabbitConnectionConfiguration.class, RabbitPublisherConfiguration.class,
                            ProfilingAmqpConfiguration.class)
                    .withPropertyValues("app.ai-profile.amqp.enabled=true",
                            "spring.rabbitmq.host=" + broker.getHost(),
                            "spring.rabbitmq.port=" + broker.getMappedPort(5672),
                            "spring.rabbitmq.username=profiling",
                            "spring.rabbitmq.password=test-password", "spring.rabbitmq.virtual-host=profiling",
                            "spring.rabbitmq.dynamic=false", "spring.rabbitmq.publisher-confirm-type=correlated",
                            "spring.rabbitmq.publisher-returns=true", "app.outbox.enabled=true",
                            "app.outbox.rabbit.exchange=reports.test",
                            "app.outbox.rabbit.search-routing-key=search.changed",
                            "app.ai-profile.amqp.host=wrong-host.invalid",
                            "app.ai-profile.amqp.virtual-host=wrong-vhost")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var admin = context.getBean(RabbitAdmin.class);
                        var factory = context.getBean(CachingConnectionFactory.class);
                        var template = context.getBean("profilingRabbitTemplate", RabbitTemplate.class);
                        var outboxTemplate = context.getBean("outboxRabbitTemplate", RabbitTemplate.class);
                        assertThat(template.getConnectionFactory()).isSameAs(factory);
                        assertThat(outboxTemplate.getConnectionFactory()).isSameAs(factory);
                        var policy = broker.execInContainer("rabbitmqctl", "set_policy", "-p", "profiling",
                                "--apply-to", "queues", "profiling-request-protection", "^profiling[.]request$",
                                "{\"max-length\":10000,\"max-length-bytes\":268435456,\"overflow\":\"reject-publish\"}");
                        assertThat(policy.getExitCode()).as(policy.getStderr()).isZero();
                        admin.initialize();
                        var queues = broker.execInContainer("rabbitmqctl", "list_queues", "-p", "profiling",
                                "name", "type", "durable");
                        assertThat(queues.getExitCode()).isZero();
                        assertThat(queues.getStdout()).contains("profiling.request\tquorum\ttrue",
                                "profiling.result\tquorum\ttrue");

                        var reportAdmin = new RabbitAdmin(factory);
                        var reportExchange = new TopicExchange("reports.test", true, false);
                        var reportQueue = new Queue("reports.test.discord", true);
                        reportAdmin.declareExchange(reportExchange);
                        reportAdmin.declareQueue(reportQueue);
                        reportAdmin.declareBinding(BindingBuilder.bind(reportQueue).to(reportExchange).with("report.*"));
                        admin.initialize();
                        var reportConfirm = new CorrelationData(UUID.randomUUID().toString());
                        outboxTemplate.send("reports.test", "report.created",
                                new Message("report".getBytes(StandardCharsets.UTF_8), new MessageProperties()), reportConfirm);
                        assertThat(reportConfirm.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
                        assertThat(reportConfirm.getReturned()).isNull();
                        assertThat(outboxTemplate.receive("reports.test.discord", 3000).getBody())
                                .isEqualTo("report".getBytes(StandardCharsets.UTF_8));
                        var outboxReturned = new CorrelationData(UUID.randomUUID().toString());
                        outboxTemplate.send("reports.test", "unmatched.route",
                                new Message(new byte[0], new MessageProperties()), outboxReturned);
                        assertThat(outboxReturned.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
                        assertThat(outboxReturned.getReturned()).isNotNull();
                        assertThat(context.getBean(RabbitHealthIndicator.class).health().getStatus().getCode())
                                .isEqualTo("UP");
                        var mixedQueues = broker.execInContainer("rabbitmqctl", "list_queues", "-p", "profiling",
                                "name", "type");
                        assertThat(mixedQueues.getStdout()).contains("reports.test.discord\tclassic",
                                "profiling.request\tquorum", "profiling.result\tquorum");

                        byte[] body = "{\"test\":true}".getBytes(StandardCharsets.UTF_8);
                        var accepted = new CorrelationData(UUID.randomUUID().toString());
                        template.send(ProfilingAmqpConfiguration.EXCHANGE, "profiling.request",
                                new Message(body, new MessageProperties()), accepted);
                        assertThat(accepted.getFuture().get(30, TimeUnit.SECONDS).ack()).isTrue();
                        assertThat(accepted.getReturned()).isNull();

                        var returned = new CorrelationData(UUID.randomUUID().toString());
                        template.send(ProfilingAmqpConfiguration.EXCHANGE, "unknown.route",
                                new Message(body, new MessageProperties()), returned);
                        assertThat(returned.getFuture().get(30, TimeUnit.SECONDS).ack()).isTrue();
                        assertThat(returned.getReturned()).isNotNull();

                        // 브로커 재기동 후 메시지 보존과 연결 재생성을 확인한다.
                        assertThat(broker.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
                        assertThat(broker.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
                        factory.resetConnection();
                        assertThat(admin.getQueueInfo("profiling.request")).isNotNull();
                        var message = template.receive("profiling.request", 3000);
                        assertThat(message).isNotNull();
                        assertThat(message.getBody()).isEqualTo(body);
                        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                                .isEqualTo(MessageDeliveryMode.PERSISTENT);
                        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
                        assertThat(admin.getQueueInfo("profiling.result")).isNotNull();

                        // 두 발행 경로를 동시에 실행해 Confirm과 메시지가 서로 섞이지 않는지 확인한다.
                        try (var executor = Executors.newFixedThreadPool(2)) {
                            var outboxPublishing = executor.submit(() -> publishConfirmed(outboxTemplate,
                                    "reports.test", "report.created", "outbox-", 5));
                            var profilingPublishing = executor.submit(() -> publishConfirmed(template,
                                    ProfilingAmqpConfiguration.EXCHANGE,
                                    ProfilingAmqpConfiguration.REQUEST_ROUTING_KEY, "profiling-", 30));
                            outboxPublishing.get(30, TimeUnit.SECONDS);
                            profilingPublishing.get(30, TimeUnit.SECONDS);
                        }
                        assertMessages(outboxTemplate, "reports.test.discord", "outbox-");
                        assertMessages(template, ProfilingAmqpConfiguration.REQUEST_QUEUE, "profiling-");

                    });
        }
    }
    private void publishConfirmed(RabbitTemplate template, String exchange, String routingKey,
                                  String prefix, int timeoutSeconds) {
        for (int i = 0; i < 10; i++) {
            String id = prefix + i;
            var properties = new MessageProperties();
            properties.setMessageId(id);
            var correlation = new CorrelationData(id);
            template.send(exchange, routingKey,
                    new Message(id.getBytes(StandardCharsets.UTF_8), properties), correlation);
            try {
                assertThat(correlation.getFuture().get(timeoutSeconds, TimeUnit.SECONDS).ack()).isTrue();
                assertThat(correlation.getReturned()).isNull();
            } catch (Exception exception) {
                throw new IllegalStateException("동시 발행 확인 실패", exception);
            }
        }
    }

    private void assertMessages(RabbitTemplate template, String queue, String prefix) {
        Set<String> messageIds = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            Message message = template.receive(queue, 3000);
            assertThat(message).isNotNull();
            String id = message.getMessageProperties().getMessageId();
            assertThat(id).startsWith(prefix);
            assertThat(message.getBody()).isEqualTo(id.getBytes(StandardCharsets.UTF_8));
            assertThat(messageIds.add(id)).isTrue();
        }
        assertThat(messageIds).hasSize(10);
        assertThat(template.receive(queue)).isNull();
    }

}
