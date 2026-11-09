package com.gift.gift.global.messaging;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxProperties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RabbitPublisher publisher = new RabbitPublisher(template, mapper,
            new OutboxProperties(10, 60, 30, 10, 2, 300));

    private MessageEnvelope envelope() {
        return new MessageEnvelope("stable-id", "product.search.sync.v1", 1, "key", "GIFT", "42",
                null, mapper.readTree("{}"), LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("ACK가 와도 mandatory return이 있으면 발행 완료로 처리하지 않는다")
    void rejectsReturnedAck() {
        doAnswer(call -> {
            CorrelationData data = call.getArgument(3);
            data.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "test.events", "missing"));
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(envelope(), "token", "test.events", "test.search").join())
                .isEqualTo(new RabbitPublisher.Result(false, "Unroutable"));
    }

    @Test
    @DisplayName("NACK는 실패로 처리하고 확인이 없으면 timeout 후 재시도한다")
    void rejectsNackAndTimeout() throws Exception {
        doAnswer(call -> {
            CorrelationData data = call.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(envelope(), "first", "test.events", "test.search")
                .join().errorType()).isEqualTo("BrokerNack");
        reset(template);
        assertThat(publisher.publish(envelope(), "second", "test.events", "test.search")
                .get(2, TimeUnit.SECONDS).errorType())
                .isEqualTo("ConfirmTimeout");
    }

    @Test
    @DisplayName("리포트 전용 Exchange 발행의 확인 채널이 끊기면 결과 불명으로 재시도한다")
    void reportExchangeKeepsUnknownConfirmAsFailure() {
        doAnswer(call -> {
            assertThat((String) call.getArgument(0)).isEqualTo("report.events");
            assertThat((String) call.getArgument(1)).isEqualTo("Report.created");
            CorrelationData data = call.getArgument(3);
            data.getFuture().completeExceptionally(new java.io.IOException("confirm channel closed"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(envelope(), "report", "report.events", "Report.created").join())
                .isEqualTo(new RabbitPublisher.Result(false, "ConfirmUnknown"));
    }

    @Test
    @DisplayName("동일 eventId 재발행에도 시도별 correlation을 구분한다")
    void correlatesEachAttemptAndKeepsPersistentMessageId() {
        doAnswer(call -> {
            CorrelationData data = call.getArgument(3);
            Message message = call.getArgument(2);
            assertThat((String) call.getArgument(0)).isEqualTo("test.events");
            assertThat((String) call.getArgument(1)).isEqualTo("test.search");
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo("stable-id");
            assertThat(message.getMessageProperties().getDeliveryMode())
                    .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            assertThat(data.getId()).isEqualTo("stable-id:token");
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(envelope(), "token", "test.events", "test.search")
                .join().confirmed()).isTrue();
    }
}
