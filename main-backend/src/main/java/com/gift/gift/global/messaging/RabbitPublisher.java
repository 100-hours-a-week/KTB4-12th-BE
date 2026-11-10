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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxProperties;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
        "${app.outbox.enabled:false} or " + "${app.payment.recovery.dispatch.enabled:false}"
)
public class RabbitPublisher {

    private final RabbitTemplate outboxRabbitTemplate;
    private final ObjectMapper mapper;
    private final OutboxProperties settings;

    public CompletableFuture<Result> publish(
            MessageEnvelope envelope,
            String claimToken,
            String exchange,
            String routingKey
    ) {
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("UTF-8");
            properties.setMessageId(envelope.eventId());
            properties.setType(envelope.eventType());
            return publish(
                    new Message(mapper.writeValueAsBytes(envelope), properties),
                    envelope.eventId() + ":" + claimToken,
                    exchange,
                    routingKey
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    new Result(false, "PublishException")
            );
        }
    }

    public CompletableFuture<Result> publish(
            Message message,
            String correlationId,
            String exchange,
            String routingKey
    ) {
        CorrelationData correlation = new CorrelationData(correlationId);
        try {
            message.getMessageProperties()
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            outboxRabbitTemplate.send(
                    exchange, routingKey, message, correlation
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    new Result(false, "PublishException")
            );
        }

        return correlation.getFuture()
                .orTimeout(settings.confirmTimeoutMs(), TimeUnit.MILLISECONDS)
                .handle((confirm, error) -> {
                    if (error != null) {
                        return new Result(
                                false,
                                error instanceof TimeoutException
                                        ? "ConfirmTimeout" : "ConfirmUnknown"
                        );
                    }
                    if (correlation.getReturned() != null) {
                        return new Result(false, "Unroutable");
                    }
                    return confirm.ack()
                            ? new Result(true, null)
                            : new Result(false, "BrokerNack");
                });
    }

    public record Result(boolean confirmed, String errorType) {
    }
}
