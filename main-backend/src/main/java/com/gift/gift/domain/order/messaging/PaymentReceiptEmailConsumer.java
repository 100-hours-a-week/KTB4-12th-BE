package com.gift.gift.domain.order.messaging;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.rabbitmq.client.Channel;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.notification.event.handler.PaymentReceiptEmailEventHandler;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryType;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService.Action;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService.Claim;
import com.gift.gift.global.messaging.MessageEnvelope;

@Slf4j
@RequiredArgsConstructor
public class PaymentReceiptEmailConsumer implements ChannelAwareMessageListener {

    public static final String OPERATOR_REPLAY_HEADER = "x-gift-operator-replay-id";

    private static final String PAYMENT_EVENT_TYPE = "payment.order.completed.v1";
    private static final String CANCELLATION_EVENT_TYPE = "payment.order.canceled.v1";
    private static final String ORDER_AGGREGATE_TYPE = "ORDER";
    private static final String CANCELLATION_AGGREGATE_TYPE = "PAYMENT_CANCELLATION";
    private static final int MAX_ENVELOPE_SIZE_BYTES = 32_768;

    private final ObjectMapper objectMapper;
    private final PaymentReceiptDeliveryTransactionService transactionService;
    private final PaymentReceiptEmailEventHandler emailHandler;

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        ReceiptMessage receiptMessage;
        try {
            receiptMessage = receiptMessageFrom(message);
        } catch (IllegalArgumentException exception) {
            log.warn(
                    "Dead-lettering invalid receipt message. deliveryTag={}, reason={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );
            channel.basicReject(deliveryTag, false);
            return;
        }

        try {
            process(receiptMessage, deliveryTag, channel);
        } catch (RuntimeException exception) {
            log.warn(
                    "Retrying receipt message after a processing error. deliveryTag={}, reason={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );
            channel.basicReject(deliveryTag, true);
        }
    }

    private void process(ReceiptMessage receiptMessage, long deliveryTag, Channel channel) throws IOException {
        Claim claim = transactionService.claim(
                receiptMessage.receiptType(),
                receiptMessage.sourceId(),
                receiptMessage.replayToken()
        );

        if (claim.action() != Action.SEND) {
            settle(claim.action(), deliveryTag, channel);
            return;
        }

        try {
            emailHandler.sendFromQueue(claim.receiptType(), claim.mailData());
        } catch (MailException exception) {
            MailFailure failure = classify(exception);
            Action action = transactionService.recordFailure(
                    claim,
                    failure.errorCode(),
                    failure.permanent()
            );

            log.warn(
                    "Receipt email send failed. deliveryTag={}, errorCode={}, permanent={}",
                    deliveryTag,
                    failure.errorCode(),
                    failure.permanent()
            );
            settle(action, deliveryTag, channel);
            return;
        }

        Action action = transactionService.markAccepted(claim);
        settle(action, deliveryTag, channel);
    }

    private ReceiptMessage receiptMessageFrom(Message message) {
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
                || envelope.eventId() == null
                || envelope.eventId().isBlank()
                || envelope.occurredAt() == null) {
            throw new IllegalArgumentException("지원하지 않는 영수증 이벤트 형식입니다.");
        }

        EventSpec eventSpec = eventSpecFrom(envelope.eventType());
        if (!eventSpec.aggregateType().equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("이벤트 종류와 aggregate 유형이 일치하지 않습니다.");
        }

        if (!envelope.eventId().equals(message.getMessageProperties().getMessageId())
                || !envelope.eventType().equals(message.getMessageProperties().getType())) {
            throw new IllegalArgumentException("AMQP 메시지 정보와 Envelope가 일치하지 않습니다.");
        }

        String replayToken = replayTokenFrom(message);
        validateRoute(message, eventSpec, replayToken);

        JsonNode payload = envelope.payload();
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("이벤트 payload가 객체가 아닙니다.");
        }

        JsonNode sourceIdNode = payload.get(eventSpec.payloadIdField());
        if (sourceIdNode == null
                || !sourceIdNode.isIntegralNumber()
                || !sourceIdNode.canConvertToLong()
                || sourceIdNode.longValue() <= 0) {
            throw new IllegalArgumentException("영수증 원본 ID가 양수가 아닙니다.");
        }

        long sourceId = sourceIdNode.longValue();
        String sourceIdText = Long.toString(sourceId);
        if (!sourceIdText.equals(envelope.aggregateId())
                || !sourceIdText.equals(envelope.deduplicationKey())) {
            throw new IllegalArgumentException("이벤트 원본 ID가 서로 일치하지 않습니다.");
        }

        return new ReceiptMessage(eventSpec.receiptType(), sourceId, replayToken);
    }

    private EventSpec eventSpecFrom(String eventType) {
        if (PAYMENT_EVENT_TYPE.equals(eventType)) {
            return new EventSpec(
                    PaymentReceiptDeliveryType.PAYMENT,
                    ORDER_AGGREGATE_TYPE,
                    "orderRecordId",
                    PaymentRabbitTopologyConfiguration.ORDER_COMPLETED_ROUTING_KEY
            );
        }

        if (CANCELLATION_EVENT_TYPE.equals(eventType)) {
            return new EventSpec(
                    PaymentReceiptDeliveryType.CANCELLATION,
                    CANCELLATION_AGGREGATE_TYPE,
                    "cancellationId",
                    PaymentRabbitTopologyConfiguration.ORDER_CANCELED_ROUTING_KEY
            );
        }

        throw new IllegalArgumentException("지원하지 않는 영수증 이벤트 종류입니다.");
    }

    private String replayTokenFrom(Message message) {
        Object header = message.getMessageProperties().getHeader(OPERATOR_REPLAY_HEADER);
        if (header == null) {
            return null;
        }

        if (!(header instanceof String token) || token.isBlank()) {
            throw new IllegalArgumentException("수동 재처리 토큰 형식이 올바르지 않습니다.");
        }

        try {
            UUID parsed = UUID.fromString(token);
            if (!parsed.toString().equalsIgnoreCase(token)) {
                throw new IllegalArgumentException("수동 재처리 토큰은 UUID 형식이어야 합니다.");
            }
            return parsed.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("수동 재처리 토큰은 UUID 형식이어야 합니다.", exception);
        }
    }

    private void validateRoute(Message message, EventSpec eventSpec, String replayToken) {
        String receivedExchange = message.getMessageProperties().getReceivedExchange();
        String receivedRoutingKey = message.getMessageProperties().getReceivedRoutingKey();

        boolean normalEventRoute = replayToken == null
                && PaymentRabbitTopologyConfiguration.EVENTS_EXCHANGE.equals(receivedExchange)
                && eventSpec.routingKey().equals(receivedRoutingKey);

        boolean operatorReplayRoute = replayToken != null
                && "".equals(receivedExchange)
                && PaymentRabbitTopologyConfiguration.RECEIPT_QUEUE.equals(receivedRoutingKey);

        if (!normalEventRoute && !operatorReplayRoute) {
            throw new IllegalArgumentException("영수증 큐에 허용되지 않은 이벤트 경로입니다.");
        }
    }

    private MailFailure classify(MailException exception) {
        if (exception instanceof MailAuthenticationException) {
            return new MailFailure("SMTP_AUTHENTICATION_FAILED", true);
        }

        if (exception instanceof MailParseException
                || exception instanceof MailPreparationException) {
            return new MailFailure("SMTP_MESSAGE_PREPARATION_FAILED", true);
        }

        Integer replyCode = smtpReplyCode(exception, new HashSet<>());
        if (replyCode != null && replyCode >= 500 && replyCode < 600) {
            return new MailFailure("SMTP_PERMANENT_REJECTION", true);
        }

        if (replyCode != null && replyCode >= 400 && replyCode < 500) {
            return new MailFailure("SMTP_TEMPORARY_REJECTION", false);
        }

        return new MailFailure("SMTP_ACCEPTANCE_UNKNOWN", false);
    }

    private Integer smtpReplyCode(Throwable throwable, Set<Throwable> visited) {
        if (throwable == null || !visited.add(throwable)) {
            return null;
        }

        if (throwable instanceof SMTPAddressFailedException exception) {
            return exception.getReturnCode();
        }

        if (throwable instanceof SMTPSendFailedException exception) {
            return exception.getReturnCode();
        }

        if (throwable instanceof MailSendException exception) {
            for (Exception nested : exception.getMessageExceptions()) {
                Integer replyCode = smtpReplyCode(nested, visited);
                if (replyCode != null) {
                    return replyCode;
                }
            }
        }

        if (throwable instanceof MessagingException exception) {
            Integer replyCode = smtpReplyCode(exception.getNextException(), visited);
            if (replyCode != null) {
                return replyCode;
            }
        }

        return smtpReplyCode(throwable.getCause(), visited);
    }

    private void settle(Action action, long deliveryTag, Channel channel) throws IOException {
        switch (action) {
            case ACK -> channel.basicAck(deliveryTag, false);
            case RETRY -> channel.basicReject(deliveryTag, true);
            case DEAD_LETTER -> channel.basicReject(deliveryTag, false);
            case SEND -> throw new IllegalStateException("발송 완료 전 Consumer 결과가 SEND로 반환됐습니다.");
        }
    }

    private record EventSpec(
            PaymentReceiptDeliveryType receiptType,
            String aggregateType,
            String payloadIdField,
            String routingKey
    ) {
    }

    private record ReceiptMessage(
            PaymentReceiptDeliveryType receiptType,
            long sourceId,
            String replayToken
    ) {
    }

    private record MailFailure(String errorCode, boolean permanent) {
    }
}
