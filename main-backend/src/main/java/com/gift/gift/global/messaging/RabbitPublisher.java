package com.gift.gift.global.messaging;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxProperties;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
public class RabbitPublisher {
    private final RabbitTemplate outboxRabbitTemplate;
    private final ObjectMapper mapper;
    private final OutboxProperties settings;
    private final RabbitRoutingProperties routing;

    public CompletableFuture<Result> publish(MessageEnvelope envelope, String claimToken, String routingKey) {
        CorrelationData correlation = new CorrelationData(envelope.eventId() + ":" + claimToken);
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("UTF-8");
            properties.setMessageId(envelope.eventId());
            properties.setType(envelope.eventType());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            outboxRabbitTemplate.send(routing.exchange(), routingKey,
                    new Message(mapper.writeValueAsBytes(envelope), properties), correlation);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(new Result(false, "PublishException"));
        }
        return correlation.getFuture().orTimeout(settings.confirmTimeoutMs(), TimeUnit.MILLISECONDS)
                .handle((confirm, error) -> {
                    if (error != null) {
                        return new Result(false, error instanceof TimeoutException ? "ConfirmTimeout" : "ConfirmUnknown");
                    }
                    if (correlation.getReturned() != null) {
                        return new Result(false, "Unroutable");
                    }
                    return confirm.ack() ? new Result(true, null) : new Result(false, "BrokerNack");
                });
    }

    public record Result(boolean confirmed, String errorType) {
    }
}
