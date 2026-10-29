package com.gift.gift.domain.notification.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationQueryRow;
import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository.Delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NotificationSseDeliverySchedulerTest {
    @Test
    @DisplayName("전달 폴링이 막혀도 SSE 전용 풀의 다른 스레드에서 임대를 갱신한다")
    void heartbeat_runsWhilePollingIsBlocked() throws Exception {
        var deliveries = mock(NotificationSseDeliveryService.class);
        var hub = mock(NotificationSseHub.class);
        var pollingStarted = new CountDownLatch(1);
        var releasePolling = new CountDownLatch(1);
        var heartbeatFinished = new CountDownLatch(1);
        var pollingThread = new AtomicReference<String>();
        var heartbeatThread = new AtomicReference<String>();
        when(hub.hasConnections()).thenAnswer(ignored -> {
            pollingThread.set(Thread.currentThread().getName());
            pollingStarted.countDown();
            releasePolling.await(10, TimeUnit.SECONDS);
            return false;
        });
        doAnswer(ignored -> {
            if (pollingStarted.await(5, TimeUnit.SECONDS)) {
                heartbeatThread.set(Thread.currentThread().getName());
                heartbeatFinished.countDown();
            }
            return null;
        }).when(hub).sendHeartbeats();
        var scheduler = new NotificationSseDeliveryScheduler(deliveries, hub);
        try {
            scheduler.afterPropertiesSet();
            assertThat(heartbeatFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(releasePolling.getCount()).isEqualTo(1);
            assertThat(pollingThread.get()).startsWith("notification-sse-");
            assertThat(heartbeatThread.get()).startsWith("notification-sse-")
                    .isNotEqualTo(pollingThread.get());
        } finally {
            releasePolling.countDown();
            scheduler.destroy();
        }
        verify(hub).closeAll();
    }

    @Test
    @DisplayName("DB 완료 처리 실패 시 SSE를 보내지 않고 다음 폴링에서 미완료 작업을 처리한다")
    void poll_doesNotSendWhenCompletionFails() {
        var deliveries = mock(NotificationSseDeliveryService.class);
        var hub = mock(NotificationSseHub.class);
        var row = new NotificationQueryRow(
                1L, NotificationType.GIFT_RECEIVED,
                "알림", "내용", null, null, LocalDateTime.of(2026, 10, 28, 0, 0), null);
        var delivery = new Delivery(
                1L, "connection", 1L, row);
        when(hub.hasConnections()).thenReturn(true);
        when(hub.nodeId()).thenReturn("node");
        when(deliveries.findDue("node", 100)).thenReturn(List.of(delivery));
        when(deliveries.completeBeforeSend(1L, "node"))
                .thenThrow(new DataAccessResourceFailureException("DB unavailable"))
                .thenReturn(true);
        var scheduler = new NotificationSseDeliveryScheduler(deliveries, hub);
        scheduler.poll();
        verify(hub, never()).send(anyString(), anyLong(), any());
        scheduler.poll();
        verify(hub).send(eq("connection"), eq(1L), any());
    }
}
