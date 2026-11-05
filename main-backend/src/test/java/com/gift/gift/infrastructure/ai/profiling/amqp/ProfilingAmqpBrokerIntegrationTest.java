package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
                    .withUserConfiguration(ProfilingAmqpConfiguration.class)
                    .withPropertyValues("app.ai-profile.amqp.enabled=true",
                            "app.ai-profile.amqp.host=" + broker.getHost(),
                            "app.ai-profile.amqp.port=" + broker.getMappedPort(5672),
                            "app.ai-profile.amqp.username=profiling",
                            "app.ai-profile.amqp.password=test-password")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var admin = context.getBean(RabbitAdmin.class);
                        var factory = context.getBean(CachingConnectionFactory.class);
                        var template = context.getBean(RabbitTemplate.class);
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
                    });
        }
    }
}
