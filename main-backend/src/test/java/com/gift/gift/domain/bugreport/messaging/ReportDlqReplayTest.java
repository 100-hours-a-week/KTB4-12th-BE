package com.gift.gift.domain.bugreport.messaging;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.MessageEnvelope;
import com.gift.gift.global.messaging.RabbitPublisher;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportDlqReplayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RabbitPublisher publisher = mock(RabbitPublisher.class);
    private final Channel channel = mock(Channel.class);
    private final ReportDlqReplay replay = new ReportDlqReplay(mapper, publisher);
    private final String id = UUID.randomUUID().toString();
    private final List<String> results = new ArrayList<>();

    private ReplayRequest request(boolean execute, int limit) {
        return new ReplayRequest(ReplayRequest.Target.DISCORD, Set.of(id), limit, execute, "ops");
    }

    private GetResponse delivery(long tag, String eventId, String destination) {
        String reportId = UUID.randomUUID().toString();
        var payload = mapper.createObjectNode().put("reportId", reportId).put("description", "private text")
            .put("reportedAt", "2026-11-08T00:00:00Z").put("category", "BUG").put("pageUrl", "")
            .put("route", "").put("viewport", "").put("userAgent", "").put("email", "").putNull("userId");
        byte[] body = mapper.writeValueAsBytes(new MessageEnvelope(eventId, EVENT_TYPE, 1, reportId, "REPORT",
            reportId, null, payload, LocalDateTime.now(), "trace"));
        var properties = new AMQP.BasicProperties.Builder().messageId(eventId).type(EVENT_TYPE)
            .contentType("application/json").headers(Map.of("delivery.channel", destination,
                "delivery.originalQueue", destination.equals("discord") ? DISCORD_QUEUE : SHEETS_QUEUE,
                ReportConsumer.RETRY_COUNT, 3, ReportConsumer.NEXT_ATTEMPT_AT, 999L)).build();
        return new GetResponse(new Envelope(tag, false, DLX, DISCORD), properties, body, 0);
    }

    @Test
    @DisplayName("dry-run은 지정 채널·ID만 확인하고 건수 제한 내 원본을 모두 반환한다")
    void dryRunIsBoundedAndDoesNotPublishOrAck() throws Exception {
        when(channel.basicGet(DLQ, false)).thenReturn(delivery(1, id, "sheets"),
            delivery(2, UUID.randomUUID().toString(), "discord"), delivery(3, id, "discord"));
        assertThat(replay.replay(channel, request(false, 2), (key, result) -> results.add(result))).isFalse();
        verify(channel, times(2)).basicGet(DLQ, false);
        verify(channel).basicNack(1, false, true);
        verify(channel).basicNack(2, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(publisher);
        assertThat(results).containsExactly("NOT_FOUND_WITHIN_LIMIT");
    }

    @Test
    @DisplayName("뒤의 지정 ID는 원본 본문·식별자를 유지하고 실패 채널에만 확인 발행 후 ACK한다")
    void replayPreservesOriginalAndRoutesOnlyToFailedChannel() throws Exception {
        var selected = delivery(2, id, "discord");
        when(channel.basicGet(DLQ, false)).thenReturn(delivery(1, id, "sheets"), selected);
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(true, null)));
        assertThat(replay.replay(channel, request(true, 2), (key, result) -> results.add(result))).isTrue();
        var message = ArgumentCaptor.forClass(Message.class);
        var order = inOrder(publisher, channel);
        order.verify(publisher).publish(message.capture(), anyString(), eq(EVENTS), eq(DISCORD));
        order.verify(channel).basicAck(2, false);
        assertThat(message.getValue().getBody()).isEqualTo(selected.getBody());
        var properties = message.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(id);
        assertThat((Number) properties.getHeader(ReportConsumer.RETRY_COUNT)).isEqualTo(0);
        assertThat((Number) properties.getHeader(ReportConsumer.NEXT_ATTEMPT_AT)).isEqualTo(0L);
        verify(channel).basicNack(1, false, true);
        assertThat(results).containsExactly("PUBLISHING", "REPLAYED");
    }

    @Test
    @DisplayName("발행 확인 실패·ACK 실패 시 원본은 반환되어 중단 후 재실행할 수 있다")
    void unknownPublishAndAckFailurePreserveOriginal() throws Exception {
        when(channel.basicGet(DLQ, false)).thenReturn(delivery(1, id, "discord"));
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(false, "ConfirmUnknown")));
        assertThatThrownBy(() -> replay.replay(channel, request(true, 1), (key, result) -> { }))
            .isInstanceOf(IOException.class);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(1, false, true);
        clearInvocations(channel);
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(true, null)));
        doThrow(new IOException("connection lost")).when(channel).basicAck(1, false);
        assertThatThrownBy(() -> replay.replay(channel, request(true, 1), (key, result) -> { }))
            .isInstanceOf(IOException.class);
        verify(channel).basicNack(1, false, true);
    }

    @Test
    @DisplayName("손상 payload와 감사 출력 실패는 외부 발행·원본 삭제를 하지 않는다")
    void invalidPayloadAndAuditFailureArePreserved() throws Exception {
        var good = delivery(1, id, "discord");
        when(channel.basicGet(DLQ, false)).thenReturn(new GetResponse(good.getEnvelope(), good.getProps(),
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0));
        assertThat(replay.replay(channel, request(true, 1), (key, result) -> results.add(result))).isFalse();
        assertThat(results).containsExactly("INVALID_PRESERVED");
        when(channel.basicGet(DLQ, false)).thenReturn(good);
        assertThatThrownBy(() -> replay.replay(channel, request(true, 1), (key, result) -> {
            throw new IllegalStateException("audit unavailable");
        })).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(publisher);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
