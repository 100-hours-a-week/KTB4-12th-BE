package com.gift.gift.domain.coupon.messaging;

import java.io.IOException;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.coupon.service.CouponRestoreTransactionService;
import com.gift.gift.global.messaging.MessageEnvelope;

@Slf4j
@RequiredArgsConstructor
public class CouponRestoreConsumer implements ChannelAwareMessageListener {

    private static final String EVENT_TYPE = "payment.order.canceled.v1";
    private static final String AGGREGATE_TYPE = "PAYMENT_CANCELLATION";
    private static final int MAX_ENVELOPE_SIZE_BYTES = 32_768;

    private final ObjectMapper objectMapper;
    private final CouponRestoreTransactionService transactionService;

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            long cancellationId = cancellationIdFrom(message);
            transactionService.restoreForCancellation(cancellationId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn(
                    "Dead-lettering invalid coupon restore message. deliveryTag={}, reason={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );
            channel.basicReject(deliveryTag, false);
            return;
        } catch (RuntimeException exception) {
            log.warn(
                    "Retrying coupon restore message. deliveryTag={}, reason={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );
            channel.basicReject(deliveryTag, true);
            return;
        }

        channel.basicAck(deliveryTag, false);
    }

    private long cancellationIdFrom(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0 || body.length > MAX_ENVELOPE_SIZE_BYTES) {
            throw new IllegalArgumentException("이벤트 본문 크기가 올바르지 않습니다.");
        }

        MessageEnvelope envelope = readEnvelope(body);
        validateEnvelope(envelope, message);

        JsonNode payload = envelope.payload();
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("이벤트 payload가 객체가 아닙니다.");
        }

        JsonNode cancellationIdNode = payload.get("cancellationId");
        if (cancellationIdNode == null
                || !cancellationIdNode.isIntegralNumber()
                || !cancellationIdNode.canConvertToLong()
                || cancellationIdNode.longValue() <= 0) {
            throw new IllegalArgumentException("취소 원장 ID가 양수가 아닙니다.");
        }

        long cancellationId = cancellationIdNode.longValue();
        String cancellationIdText = Long.toString(cancellationId);
        if (!cancellationIdText.equals(envelope.aggregateId())
                || !cancellationIdText.equals(envelope.deduplicationKey())) {
            throw new IllegalArgumentException("이벤트의 취소 원장 ID가 서로 일치하지 않습니다.");
        }

        return cancellationId;
    }

    private MessageEnvelope readEnvelope(byte[] body) {
        try {
            return objectMapper.readValue(body, MessageEnvelope.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("이벤트 JSON을 읽을 수 없습니다.", exception);
        }
    }

    private void validateEnvelope(MessageEnvelope envelope, Message message) {
        if (envelope == null
                || envelope.schemaVersion() != 1
                || !EVENT_TYPE.equals(envelope.eventType())
                || !AGGREGATE_TYPE.equals(envelope.aggregateType())
                || envelope.eventId() == null
                || envelope.eventId().isBlank()
                || envelope.occurredAt() == null) {
            throw new IllegalArgumentException("지원하지 않는 취소 이벤트 형식입니다.");
        }

        if (!envelope.eventId().equals(message.getMessageProperties().getMessageId())
                || !EVENT_TYPE.equals(message.getMessageProperties().getType())) {
            throw new IllegalArgumentException("AMQP 메시지 정보와 Envelope가 일치하지 않습니다.");
        }
    }
}
