package com.gift.gift.domain.roulette.messaging;

import java.time.LocalDateTime;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.roulette.service.RouletteGrantTransactionService;
import com.gift.gift.global.messaging.MessageEnvelope;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RouletteGiftConsumerTest {

    private static final long ORDER_RECORD_ID = 101L;
    private static final String EVENT_ID = "payment-completed-event-101";
    private static final String EVENT_TYPE = "payment.order.completed.v1";
    private static final LocalDateTime PAYMENT_COMPLETED_AT =
            LocalDateTime.of(2026, 10, 26, 23, 59, 59);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RouletteGrantTransactionService transactionService =
            mock(RouletteGrantTransactionService.class);
    private final Channel channel = mock(Channel.class);
    private final RouletteGiftConsumer consumer =
            new RouletteGiftConsumer(objectMapper, transactionService);

    @Test
    @DisplayName("유효한 결제 이벤트는 지급 트랜잭션 처리 뒤 ACK한다")
    void onMessage_validEvent_grantsThenAcknowledges() throws Exception {
        consumer.onMessage(event(ORDER_RECORD_ID, Long.toString(ORDER_RECORD_ID)), channel);

        var order = inOrder(transactionService, channel);
        order.verify(transactionService).grantForCompletedPayment(
                ORDER_RECORD_ID,
                PAYMENT_COMPLETED_AT
        );
        order.verify(channel).basicAck(17L, false);
    }

    @Test
    @DisplayName("Envelope와 payload의 주문 ID가 다르면 재전달 없이 NACK해 DLQ로 보낸다")
    void onMessage_mismatchedOrderId_nacksWithoutRequeue() throws Exception {
        consumer.onMessage(event(ORDER_RECORD_ID, "999"), channel);

        verifyNoInteractions(transactionService);
        verify(channel).basicNack(17L, false, false);
        verify(channel, never()).basicAck(17L, false);
    }

    @Test
    @DisplayName("일시적인 DB 오류는 재전달을 위해 NACK한다")
    void onMessage_temporaryDatabaseError_nacksWithRequeue() throws Exception {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(transactionService)
                .grantForCompletedPayment(ORDER_RECORD_ID, PAYMENT_COMPLETED_AT);

        consumer.onMessage(event(ORDER_RECORD_ID, Long.toString(ORDER_RECORD_ID)), channel);

        verify(channel).basicNack(17L, false, true);
        verify(channel, never()).basicAck(17L, false);
    }

    @Test
    @DisplayName("복구할 수 없는 업무 데이터 오류는 재전달 없이 NACK해 DLQ로 보낸다")
    void onMessage_permanentDataError_nacksWithoutRequeue() throws Exception {
        doThrow(new IllegalStateException("주문 데이터 불일치"))
                .when(transactionService)
                .grantForCompletedPayment(ORDER_RECORD_ID, PAYMENT_COMPLETED_AT);

        consumer.onMessage(event(ORDER_RECORD_ID, Long.toString(ORDER_RECORD_ID)), channel);

        verify(channel).basicNack(17L, false, false);
        verify(channel, never()).basicAck(17L, false);
    }

    private Message event(long orderRecordId, String aggregateId) {
        var payload = objectMapper.createObjectNode()
                .put("orderRecordId", orderRecordId);
        var envelope = new MessageEnvelope(
                EVENT_ID,
                EVENT_TYPE,
                1,
                Long.toString(orderRecordId),
                "ORDER",
                aggregateId,
                null,
                payload,
                PAYMENT_COMPLETED_AT,
                null
        );
        var properties = new MessageProperties();
        properties.setDeliveryTag(17L);
        properties.setMessageId(EVENT_ID);
        properties.setType(EVENT_TYPE);
        return new Message(objectMapper.writeValueAsBytes(envelope), properties);
    }
}
