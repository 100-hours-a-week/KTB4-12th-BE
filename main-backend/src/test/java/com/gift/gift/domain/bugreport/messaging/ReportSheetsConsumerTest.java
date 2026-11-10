package com.gift.gift.domain.bugreport.messaging;

import java.util.concurrent.CompletableFuture;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.google.GoogleBugReportClient;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportSheetsConsumerTest {
    private final GoogleBugReportClient google = mock(GoogleBugReportClient.class);
    private final ReportCooldown cooldown = mock(ReportCooldown.class);
    private final RabbitPublisher publisher = mock(RabbitPublisher.class);
    private final Channel channel = mock(Channel.class);
    private final Runnable recover = mock(Runnable.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportConsumer consumer = new ReportConsumer(mapper, "sheets", SHEETS, SHEETS_QUEUE,
        google::deliver, cooldown, publisher, meters, recover, () -> { });

    @BeforeEach
    void setup() {
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(true, null)));
    }

    @Test
    @DisplayName("기존 ID 완료도 ACK하고 중복 방지 지표를 기록하며 Discord 재발행은 하지 않는다")
    void acknowledgesExistingIdAndRecordsMetric() throws Exception {
        when(google.deliver(any())).thenReturn(new ReportDeliveryResult(true, false, 0, false, "AlreadyRecorded"));
        consumer.onMessage(ReportDiscordConsumerTest.event(mapper), channel);
        verify(channel).basicAck(17, false);
        verifyNoInteractions(publisher, recover);
        assertThat(meters.get("report.sheets.deliveries").tag("outcome", "already_recorded").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("quota 대기는 Sheets 재시도 key와 공유 시각을 보존하고 원문은 변경하지 않는다")
    void quotaOnlyRetriesSheetsAfterConfirm() throws Exception {
        when(google.deliver(any())).thenReturn(new ReportDeliveryResult(false, false, 65000, true, "QuotaExceeded"));
        when(cooldown.extend(65000, true)).thenReturn(1800000000000L);
        Message original = ReportDiscordConsumerTest.event(mapper);
        consumer.onMessage(original, channel);
        var message = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(message.capture(), anyString(), eq(RETRY), eq(SHEETS));
        assertThat(message.getValue().getBody()).isEqualTo(original.getBody());
        assertThat(message.getValue().getMessageProperties().getHeaders()).containsEntry("delivery.channel", "sheets")
            .containsEntry("delivery.originalQueue", SHEETS_QUEUE).containsEntry("delivery.retryCount", 1)
            .containsEntry("delivery.nextAttemptAt", 1800000000000L);
        verify(channel).basicAck(17, false);
        verify(publisher, never()).publish(any(Message.class), anyString(), anyString(), eq(DISCORD));
    }

    @Test
    @DisplayName("영구 오류와 재시도 소진은 Sheets 원문을 공용 DLQ에 보존한다")
    void deadLettersPermanentAndExhaustedMessages() {
        for (boolean permanent : new boolean[]{true, false}) {
            clearInvocations(publisher);
            when(google.deliver(any())).thenReturn(new ReportDeliveryResult(false, permanent, 0, false, "Failure"));
            Message original = ReportDiscordConsumerTest.event(mapper);
            original.getMessageProperties().setHeader("delivery.retryCount", permanent ? 0 : 3);
            consumer.onMessage(original, channel);
            verify(publisher).publish(any(Message.class), anyString(), eq(DLX), eq(SHEETS));
        }
    }

    @Test
    @DisplayName("Redis 장애는 Sheets 호출이나 업무 횟수 소진 없이 대기하며 confirm 실패는 ACK하지 않는다")
    void defersRedisFailureAndRetainsUnconfirmedOriginal() throws Exception {
        when(cooldown.remainingMs(0)).thenThrow(new RedisConnectionFailureException("down"));
        Message original = ReportDiscordConsumerTest.event(mapper);
        consumer.onMessage(original, channel);
        var message = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(message.capture(), anyString(), eq(RETRY), eq(SHEETS));
        assertThat(message.getValue().getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 0);
        verifyNoInteractions(google);
        clearInvocations(channel);
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(false, "Unroutable")));
        consumer.onMessage(original, channel);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(recover).run();
    }
}
