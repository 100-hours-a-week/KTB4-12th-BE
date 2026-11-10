package com.gift.gift.domain.coupon.messaging;

import java.time.LocalDateTime;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.coupon.service.CouponRestoreTransactionService;
import com.gift.gift.global.messaging.MessageEnvelope;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CouponRestoreConsumerTest {

    private static final long CANCELLATION_ID = 101L;
    private static final String EVENT_ID = "coupon-restore-event-101";
    private static final String EVENT_TYPE = "payment.order.canceled.v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CouponRestoreTransactionService transactionService = mock(CouponRestoreTransactionService.class);
    private final Channel channel = mock(Channel.class);
    private final CouponRestoreConsumer consumer = new CouponRestoreConsumer(objectMapper, transactionService);

    @Test
    @DisplayName("유효한 취소 이벤트는 DB 복구 완료 뒤 ACK한다")
    void onMessage_validEvent_restoresThenAcknowledges() throws Exception {
        consumer.onMessage(event(CANCELLATION_ID, Long.toString(CANCELLATION_ID)), channel);

        var order = inOrder(transactionService, channel);
        order.verify(transactionService).restoreForCancellation(CANCELLATION_ID);
        order.verify(channel).basicAck(17L, false);
        verify(channel, never()).basicReject(17L, false);
    }

    @Test
    @DisplayName("Envelope와 payload의 ID가 다르면 DLQ로 보낸다")
    void onMessage_mismatchedCancellationId_rejectsWithoutRequeue() throws Exception {
        consumer.onMessage(event(CANCELLATION_ID, "999"), channel);

        verifyNoInteractions(transactionService);
        verify(channel).basicReject(17L, false);
        verify(channel, never()).basicAck(17L, false);
    }

    @Test
    @DisplayName("취소 상태 불일치처럼 재시도해도 해결되지 않는 오류는 DLQ로 보낸다")
    void onMessage_permanentBusinessError_rejectsWithoutRequeue() throws Exception {
        doThrow(new IllegalStateException("취소가 완료되지 않았습니다."))
                .when(transactionService)
                .restoreForCancellation(CANCELLATION_ID);

        consumer.onMessage(event(CANCELLATION_ID, Long.toString(CANCELLATION_ID)), channel);

        verify(channel).basicReject(17L, false);
        verify(channel, never()).basicAck(17L, false);
    }

    @Test
    @DisplayName("일시적인 DB 오류는 재전달을 요청한다")
    void onMessage_temporaryDatabaseError_rejectsWithRequeue() throws Exception {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(transactionService)
                .restoreForCancellation(CANCELLATION_ID);

        consumer.onMessage(event(CANCELLATION_ID, Long.toString(CANCELLATION_ID)), channel);

        verify(channel).basicReject(17L, true);
        verify(channel, never()).basicAck(17L, false);
    }

    private Message event(long cancellationId, String aggregateId) {
        var payload = objectMapper.createObjectNode()
                .put("cancellationId", cancellationId);
        var envelope = new MessageEnvelope(
                EVENT_ID,
                EVENT_TYPE,
                1,
                Long.toString(cancellationId),
                "PAYMENT_CANCELLATION",
                aggregateId,
                null,
                payload,
                LocalDateTime.of(2026, 11, 8, 12, 0),
                null
        );
        var properties = new MessageProperties();
        properties.setDeliveryTag(17L);
        properties.setMessageId(EVENT_ID);
        properties.setType(EVENT_TYPE);
        return new Message(objectMapper.writeValueAsBytes(envelope), properties);
    }
}
