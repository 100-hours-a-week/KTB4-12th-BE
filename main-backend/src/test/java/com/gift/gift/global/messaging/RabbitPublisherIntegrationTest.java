package com.gift.gift.global.messaging;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxProperties;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitPublisherIntegrationTest {
    @Test
    @DisplayName("실제 RabbitMQ에서 persistent 발행 확인과 미라우팅 return을 검증한다")
    void confirmsRoutedMessagesAndRejectsUnroutableMessages() throws Exception {
        try (var rabbit = new GenericContainer<>("rabbitmq:4.2.0-alpine").withExposedPorts(5672)) {
            rabbit.start();
            CachingConnectionFactory connection =
                    new CachingConnectionFactory(rabbit.getHost(), rabbit.getMappedPort(5672));
            try {
                connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
                connection.setPublisherReturns(true);
                RabbitPublisherConfiguration topology = new RabbitPublisherConfiguration();
                var template = topology.outboxRabbitTemplate(connection);
                RabbitAdmin admin = new RabbitAdmin(connection);
                admin.declareExchange(new DirectExchange("test.events", true, false));
                var mapper = new ObjectMapper();
                var publisher = new RabbitPublisher(template, mapper,
                        new OutboxProperties(10, 10, 60, 5000, 10, 2, 300),
                        new RabbitRoutingProperties("test.events", "test.notification", "test.search"));
                var event = new MessageEnvelope("event-1", "notification.create.v1", 1, "dedup",
                        "GIFT", "42", null, mapper.readTree("{}"), LocalDateTime.now(), null);
                assertThat(publisher.publish(event, "unrouted", "test.notification").get(10, TimeUnit.SECONDS).errorType())
                        .isEqualTo("Unroutable");
                admin.declareQueue(new org.springframework.amqp.core.Queue("test.queue", true));
                admin.declareBinding(new org.springframework.amqp.core.Binding("test.queue",
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        "test.events", "test.notification", null));
                assertThat(publisher.publish(event, "routed", "test.notification").get(10, TimeUnit.SECONDS).confirmed()).isTrue();
                var delivered = template.receive("test.queue", 5000);
                assertThat(delivered).isNotNull();
                assertThat(delivered.getMessageProperties().getMessageId()).isEqualTo("event-1");
                assertThat(mapper.readTree(delivered.getBody()).get("eventId").asText()).isEqualTo("event-1");
            } finally {
                connection.destroy();
            }
        }
    }
}
