package com.gift.gift.domain.notification.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class NotificationSseHubTest {
    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final NotificationSseHub hub = new NotificationSseHub(container, mapper, metrics);
    private final Map<String, MessageListener> listeners = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        doAnswer(call -> {
            ChannelTopic topic = call.getArgument(1);
            listeners.put(topic.getTopic(), call.getArgument(0));
            return null;
        }).when(container).addMessageListener(any(), any(ChannelTopic.class));
        doAnswer(call -> {
            ChannelTopic topic = call.getArgument(1);
            listeners.remove(topic.getTopic(), call.getArgument(0));
            return null;
        }).when(container).removeMessageListener(any(), any(ChannelTopic.class));
    }

    @AfterEach
    void cleanup() { hub.destroy(); metrics.close(); }

    @Test
    @DisplayName("첫 탭에서 구독하고 마지막 탭이 닫힐 때만 구독을 해제한다")
    void multipleTabs_shareSubscriptionUntilLastDisconnect() {
        var a = new SseEmitterProbe(hub.connect(1L));
        var b = new SseEmitterProbe(hub.connect(1L));
        assertThat(a.data).contains("connected");
        verify(container, times(1)).addMessageListener(any(), any(ChannelTopic.class));
        a.complete();
        verify(container, never()).removeMessageListener(any(), any(ChannelTopic.class));
        publish(1L, 1L);
        b.awaitNotifications(1);
        assertThat(a.notifications()).isEmpty();
        b.complete();
        assertThat(listeners).isEmpty();
        assertThat(hub.hasConnections()).isFalse();
    }

    @Test
    @DisplayName("대상 사용자의 모든 탭에만 보내며 채널과 수신자가 다른 메시지는 버린다")
    void message_isolatesRecipientAndRejectsMismatchedChannel() {
        var a = new SseEmitterProbe(hub.connect(1L));
        var b = new SseEmitterProbe(hub.connect(1L));
        var other = new SseEmitterProbe(hub.connect(2L));
        publish(1L, 2L);
        assertThat(metrics.get("notification.redis.received").tag("result", "invalid").counter().count()).isEqualTo(1);
        publish(1L, 1L);
        a.awaitNotifications(1); b.awaitNotifications(1);
        assertThat(other.notifications()).isEmpty();
        assertThat(a.notifications()).isEqualTo(b.notifications());
    }

    @Test
    @DisplayName("실제 채널 구독 완료 전에는 resync를 보내지 않고 완료된 사용자에게만 보낸다")
    void subscriptionConfirmation_resyncsOnlyMatchingRecipient() {
        var a = new SseEmitterProbe(hub.connect(1L));
        var b = new SseEmitterProbe(hub.connect(2L));
        assertThat(a.data).doesNotContain("resync");
        confirm(1L);
        await().atMost(Duration.ofSeconds(5)).until(() -> a.data.contains("resync"));
        assertThat(b.data).doesNotContain("resync");
    }

    @Test
    @DisplayName("종료한 구독의 늦은 콜백은 재접속한 새 탭에 전달하지 않는다")
    void retiredListener_doesNotSendToNewConnection() {
        var old = new SseEmitterProbe(hub.connect(1L));
        var listener = listeners.get(channel(1L));
        old.complete();
        var fresh = new SseEmitterProbe(hub.connect(1L));
        ((SubscriptionListener) listener).onChannelSubscribed(bytes(channel(1L)), 1);
        listener.onMessage(message(1L, 1L), null);
        assertThat(fresh.data).doesNotContain("resync");
        assertThat(fresh.notifications()).isEmpty();
        publish(1L, 1L);
        fresh.awaitNotifications(1);
    }

    @Test
    @DisplayName("동시 탭 연결과 종료 후에도 남은 탭의 구독을 유지한다")
    void concurrentConnections_keepRemainingSubscription() throws Exception {
        var initial = new SseEmitterProbe(hub.connect(1L));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 30; i++) tasks.add(executor.submit(() -> {
                var tab = new SseEmitterProbe(hub.connect(1L));
                tab.complete();
            }));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        verify(container, times(1)).addMessageListener(any(), any(ChannelTopic.class));
        verify(container, never()).removeMessageListener(any(), any(ChannelTopic.class));
        publish(1L, 1L); initial.awaitNotifications(1);
        initial.complete();
        assertThat(listeners).isEmpty();
    }

    @Test
    @DisplayName("시간 초과와 오류는 연결과 구독을 정리한다")
    void timeoutAndError_removeConnections() {
        var a = new SseEmitterProbe(hub.connect(1L));
        var b = new SseEmitterProbe(hub.connect(2L));
        a.timeout(); b.error();
        assertThat(listeners).isEmpty();
        assertThat(hub.hasConnections()).isFalse();
    }

    @Test
    @DisplayName("전송 실패 연결을 제거하고 같은 사용자의 다른 탭은 유지한다")
    void sendFailure_keepsOtherTabs() {
        new SseEmitterProbe(hub.connect(1L), () -> { throw new java.io.IOException("closed"); });
        var good = new SseEmitterProbe(hub.connect(1L));
        publish(1L, 1L); good.awaitNotifications(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> metrics.get("notification.sse.connections").gauge().value() == 1);
        assertThat(listeners).containsKey(channel(1L));
    }

    @Test
    @DisplayName("느린 연결은 후속 알림을 버리되 연결을 유지하고 재구독 resync는 쓰기 완료 후 전송한다")
    void busyConnection_dropsNotificationButDefersResync() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slow = new SseEmitterProbe(hub.connect(1L), () -> {
            entered.countDown(); release.await(10, TimeUnit.SECONDS);
        });
        var fast = new SseEmitterProbe(hub.connect(2L));
        try {
            publish(1L, 1L);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            publish(1L, 1L);
            confirm(1L);
            publish(2L, 2L); fast.awaitNotifications(1);
            assertThat(slow.data).doesNotContain("resync");
            assertThat(hub.hasConnections()).isTrue();
        } finally { release.countDown(); }
        slow.awaitNotifications(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> slow.data.contains("resync"));
        assertThat(metrics.get("notification.sse.dropped").tag("reason", "busy").tag("event", "notification").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("전송 풀과 큐 포화 시 호출 스레드에서 쓰지 않고 연결을 유지한다")
    void saturatedPool_dropsAndKeepsConnection() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try {
            for (long id = 1; id <= 4; id++) {
                new SseEmitterProbe(hub.connect(id), () -> { entered.countDown(); release.await(10, TimeUnit.SECONDS); });
                publish(id, id);
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (long id = 5; id <= 104; id++) { new SseEmitterProbe(hub.connect(id)); publish(id, id); }
            var rejected = new SseEmitterProbe(hub.connect(105L));
            Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> publish(105L, 105L));
            assertThat(rejected.notifications()).isEmpty();
            assertThat(metrics.get("notification.sse.dropped").tag("reason", "queue_full").counter().count()).isEqualTo(1);
            assertThat(metrics.get("notification.sse.connections").gauge().value()).isEqualTo(105);
        } finally { release.countDown(); }
    }

    private void confirm(Long user) {
        ((SubscriptionListener) listeners.get(channel(user))).onChannelSubscribed(bytes(channel(user)), 1);
    }
    private void publish(Long channelUser, Long bodyUser) {
        listeners.get(channel(channelUser)).onMessage(message(channelUser, bodyUser), null);
    }
    private DefaultMessage message(Long channelUser, Long bodyUser) {
        return new DefaultMessage(bytes(channel(channelUser)), mapper.writeValueAsBytes(event(bodyUser)));
    }
    static NotificationCreatedEvent event(Long recipient) {
        return new NotificationCreatedEvent(recipient, new NotificationListItem(10L, NotificationType.FRIEND_REQUEST_RECEIVED,
                "친구 요청", "친구 요청이 도착했습니다.", NotificationReferenceType.FRIEND_REQUEST, 501L,
                LocalDateTime.of(2026, 10, 29, 0, 0), false));
    }
    private String channel(Long user) { return NotificationRedisPublisher.channel(user); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
