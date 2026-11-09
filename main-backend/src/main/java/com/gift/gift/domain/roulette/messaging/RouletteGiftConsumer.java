package com.gift.gift.domain.roulette.messaging;

import java.io.IOException;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.roulette.service.RouletteGrantTransactionService;
import com.gift.gift.global.messaging.MessageEnvelope;

@Slf4j
@RequiredArgsConstructor
public class RouletteGiftConsumer implements ChannelAwareMessageListener {

    private static final String EVENT_TYPE = "payment.order.completed.v1";
    private static final String AGGREGATE_TYPE = "ORDER";
    private static final int MAX_ENVELOPE_SIZE_BYTES = 32_768;

    private final ObjectMapper objectMapper;
    private final RouletteGrantTransactionService transactionService;

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            MessageEnvelope envelope = envelopeFrom(message);
            long orderRecordId = orderRecordIdFrom(envelope);
            transactionService.grantForCompletedPayment(orderRecordId, envelope.occurredAt());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn(
                    "Dead-lettering invalid roulette gift message. deliveryTag={}, reason={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );
            channel.basicNack(deliveryTag, false, false);
            return;
        } catch (RuntimeException exception) {
            log.warn(
                    "Retrying roulette gift message. deliveryTag={}, reason={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        channel.basicAck(deliveryTag, false);
    }

    private MessageEnvelope envelopeFrom(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0 || body.length > MAX_ENVELOPE_SIZE_BYTES) {
            throw new IllegalArgumentException("이벤트 본문 크기가 올바르지 않습니다.");
        }

        MessageEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("이벤트 JSON을 읽을 수 없습니다.", exception);
        }

        if (envelope == null
                || envelope.schemaVersion() != 1
                || !EVENT_TYPE.equals(envelope.eventType())
                || !AGGREGATE_TYPE.equals(envelope.aggregateType())
                || envelope.eventId() == null
                || envelope.eventId().isBlank()
                || envelope.occurredAt() == null) {
            throw new IllegalArgumentException("지원하지 않는 결제 완료 이벤트 형식입니다.");
        }

        if (!envelope.eventId().equals(message.getMessageProperties().getMessageId())
                || !EVENT_TYPE.equals(message.getMessageProperties().getType())) {
            throw new IllegalArgumentException("AMQP 메시지 정보와 Envelope가 일치하지 않습니다.");
        }

        return envelope;
    }

    private long orderRecordIdFrom(MessageEnvelope envelope) {
        JsonNode payload = envelope.payload();
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("이벤트 payload가 객체가 아닙니다.");
        }

        JsonNode orderRecordIdNode = payload.get("orderRecordId");
        if (orderRecordIdNode == null
                || !orderRecordIdNode.isIntegralNumber()
                || !orderRecordIdNode.canConvertToLong()
                || orderRecordIdNode.longValue() <= 0) {
            throw new IllegalArgumentException("주문 레코드 ID가 양수가 아닙니다.");
        }

        long orderRecordId = orderRecordIdNode.longValue();
        String orderRecordIdText = Long.toString(orderRecordId);
        if (!orderRecordIdText.equals(envelope.aggregateId())
                || !orderRecordIdText.equals(envelope.deduplicationKey())) {
            throw new IllegalArgumentException("이벤트의 주문 ID가 서로 일치하지 않습니다.");
        }

        return orderRecordId;
    }
}
