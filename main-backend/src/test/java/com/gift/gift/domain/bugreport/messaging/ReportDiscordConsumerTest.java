package com.gift.gift.domain.bugreport.messaging;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.MessageEnvelope;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportDiscordConsumerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DiscordWebhookClient discord = mock(DiscordWebhookClient.class);
    private final ReportDiscordCooldown cooldown = mock(ReportDiscordCooldown.class);
    private final RabbitPublisher publisher = mock(RabbitPublisher.class);
    private final Runnable recover = mock(Runnable.class);
    private final Runnable recovered = mock(Runnable.class);
    private final Channel channel = mock(Channel.class);
    private final ReportDiscordConsumer consumer = new ReportDiscordConsumer(mapper, discord, "http://test/webhook",
        cooldown, publisher, new SimpleMeterRegistry(), recover, recovered);

    @BeforeEach
    void setup() {
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(true, null)));
    }

    static Message event(ObjectMapper mapper) {
        String id = "00000000-0000-4000-8000-000000000001";
        var payload = mapper.createObjectNode().put("reportId", id).put("description", "a".repeat(8000))
            .put("category", "버그").put("pageUrl", "").put("route", "/").put("viewport", "100x100")
            .put("userAgent", "test").put("reportedAt", "2026-11-07T00:00:00Z").put("email", "").putNull("userId");
        var envelope = new MessageEnvelope("event-1", EVENT_TYPE, 1, id, "REPORT", id, null,
            payload, LocalDateTime.now(), null);
        var properties = new MessageProperties();
        properties.setDeliveryTag(17);
        properties.setMessageId("event-1");
        properties.setType(EVENT_TYPE);
        return new Message(mapper.writeValueAsBytes(envelope), properties);
    }

    @Test
    @DisplayName("Discord 성공 뒤 ACK하고 사용자 멘션 및 표시 길이를 제한한다")
    void acknowledgesOnlySuccessfulDelivery() throws Exception {
        when(discord.send(anyString(), anyString())).thenReturn(result(true, false, 0));
        consumer.onMessage(event(mapper), channel);
        var order = inOrder(discord, channel);
        order.verify(discord).send(anyString(), anyString());
        order.verify(channel).basicAck(17, false);
        verifyNoInteractions(publisher, recover);
        var body = ArgumentCaptor.forClass(String.class);
        verify(discord).send(anyString(), body.capture());
        var payload = mapper.readTree(body.getValue());
        assertThat(payload.path("allowed_mentions").path("parse").size()).isZero();
        assertThat(payload.path("embeds").get(0).path("description").asText().length()).isLessThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("최초 실패는 retryCount 1로 원문을 보존하고 confirm 뒤 ACK한다")
    void retriesFailureAfterConfirm() throws Exception {
        when(discord.send(anyString(), anyString())).thenReturn(result(false, false, 0));
        Message original = event(mapper);
        var confirm = new CompletableFuture<RabbitPublisher.Result>();
        when(publisher.publish(any(Message.class), anyString(), eq(RETRY), eq(DISCORD))).thenAnswer(call -> {
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            confirm.complete(new RabbitPublisher.Result(true, null));
            return confirm;
        });
        consumer.onMessage(original, channel);
        Message retry = transferred(RETRY);
        assertThat(retry.getBody()).isEqualTo(original.getBody());
        assertThat(retry.getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 1)
            .containsEntry("delivery.channel", "discord");
        verify(channel).basicAck(17, false);
    }

    @Test
    @DisplayName("추가 3회 소진 또는 영구 오류는 공용 DLQ로 전달한다")
    void deadLettersExhaustedAndPermanentFailures() throws Exception {
        when(discord.send(anyString(), anyString())).thenReturn(result(false, false, 0));
        Message exhausted = event(mapper);
        exhausted.getMessageProperties().setHeader("delivery.retryCount", 3);
        consumer.onMessage(exhausted, channel);
        assertThat(transferred(DLX).getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 3);
        clearInvocations(publisher);
        when(discord.send(anyString(), anyString())).thenReturn(result(false, true, 0));
        consumer.onMessage(event(mapper), channel);
        assertThat(transferred(DLX).getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 0);
    }

    @Test
    @DisplayName("공유 대기 중이거나 Redis 조회 실패면 호출 없이 횟수를 유지한다")
    void defersWithoutAttemptOnCooldownOrRedisFailure() throws Exception {
        when(cooldown.remainingMs(0)).thenReturn(60000L);
        consumer.onMessage(event(mapper), channel);
        assertThat(transferred(RETRY).getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 0);
        clearInvocations(publisher);
        when(cooldown.remainingMs(0)).thenThrow(new RedisConnectionFailureException("unavailable"));
        consumer.onMessage(event(mapper), channel);
        assertThat(transferred(RETRY).getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 0);
        verifyNoInteractions(discord);
    }

    @Test
    @DisplayName("429는 공유 재개 시각을 보존하고 Redis 갱신 실패는 횟수를 소진하지 않는다")
    void preservesRateLimitAndHandlesFailedUpdate() throws Exception {
        when(discord.send(anyString(), anyString())).thenReturn(result(false, false, 65000));
        when(cooldown.extend(65000, false)).thenReturn(1800000000000L);
        consumer.onMessage(event(mapper), channel);
        assertThat(transferred(RETRY).getMessageProperties().getHeaders())
            .containsEntry("delivery.nextAttemptAt", 1800000000000L).containsEntry("delivery.retryCount", 1);
        clearInvocations(publisher);
        when(cooldown.extend(65000, false)).thenThrow(new RedisConnectionFailureException("unavailable"));
        consumer.onMessage(event(mapper), channel);
        assertThat(transferred(RETRY).getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 0);
    }

    @Test
    @DisplayName("잘못된 JSON과 전달 메타데이터는 원문을 보존해 DLQ로 보낸다")
    void deadLettersMalformedBodyAndHeaders() throws Exception {
        Message malformed = new Message("{broken".getBytes(), new MessageProperties());
        consumer.onMessage(malformed, channel);
        assertThat(transferred(DLX).getBody()).isEqualTo(malformed.getBody());
        clearInvocations(publisher);
        Message invalidHeader = event(mapper);
        invalidHeader.getMessageProperties().setHeader("delivery.retryCount", "wrong");
        consumer.onMessage(invalidHeader, channel);
        assertThat(transferred(DLX).getMessageProperties().getHeaders()).containsEntry("delivery.errorCode", "InvalidEnvelope");
        verifyNoInteractions(discord, cooldown);
    }

    @Test
    @DisplayName("NACK·return·timeout·연결 실패는 원본 ACK 없이 리스너 복구를 요청한다")
    void retainsOriginalOnUnconfirmedTransfer() throws Exception {
        when(discord.send(anyString(), anyString())).thenReturn(result(false, false, 0));
        for (String reason : new String[]{"BrokerNack", "Unroutable", "ConfirmTimeout", "ConfirmUnknown"}) {
            when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(false, reason)));
            consumer.onMessage(event(mapper), channel);
        }
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(recover, times(4)).run();
    }

    @Test
    @DisplayName("외부 성공 후 ACK 연결 단절은 재전달을 허용하며 다시 성공 처리한다")
    void acceptsDuplicateAfterLostAck() throws Exception {
        when(discord.send(anyString(), anyString())).thenReturn(result(true, false, 0));
        doThrow(new java.io.IOException("connection lost")).doNothing().when(channel).basicAck(17, false);
        Message original = event(mapper);
        consumer.onMessage(original, channel);
        consumer.onMessage(original, channel);
        verify(discord, times(2)).send(anyString(), anyString());
        verify(recover).run();
        verify(recovered).run();
    }

    private DiscordWebhookClient.DeliveryResult result(boolean success, boolean permanent, long delay) {
        return new DiscordWebhookClient.DeliveryResult(success, permanent, delay, false, "TestFailure");
    }

    private Message transferred(String exchange) {
        var capture = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(capture.capture(), anyString(), eq(exchange), eq(DISCORD));
        return capture.getValue();
    }
}
