package com.gift.gift.domain.notification.service;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSseHubTest {

    private final NotificationSseConnectionService registry = org.mockito.Mockito.mock(NotificationSseConnectionService.class);
    private final NotificationSseHub hub = new NotificationSseHub(registry);
    private String lastConnectionId;

    @AfterEach
    void cleanup() {
        hub.destroy();
    }

    @Test
    @DisplayName("연결 직후 알림 없이도 connected 이벤트를 먼저 전송한다")
    void connect_sendsConnectedEvent() {
        EmitterProbe emitter = connect(1L);

        assertThat(emitter.initialData()).extracting(item -> ((DataWithMediaType) item).getData())
                .containsExactly("event:connected\ndata:", "connected", "\n\n");
        assertThat(emitter.sentData()).isEmpty();
    }

    @Test
    @DisplayName("알림은 해당 수신자의 SSE 연결에만 전달한다")
    void publish_sendsOnlyToRecipientConnections() {
        EmitterProbe recipient = connect(1L);
        String recipientConnection = lastConnectionId;
        EmitterProbe anotherRecipient = connect(2L);
        NotificationListItem notification = notification();

        hub.send(recipientConnection, 1L, notification);

        recipient.awaitSend();
        assertThat(recipient.sentData()).anySatisfy(item ->
                assertThat(((DataWithMediaType) item).getData()).isSameAs(notification)
        );
        assertThat(anotherRecipient.sentData()).isEmpty();
    }

    @Test
    @DisplayName("연결이 완료되면 heartbeat 대상에서 제거한다")
    void completion_removesConnection() {
        EmitterProbe emitter = connect(1L);

        emitter.completion().run();
        hub.sendHeartbeats();

        assertThat(emitter.sentData()).isEmpty();
    }

    @Test
    @DisplayName("연결이 시간 초과되면 heartbeat 대상에서 제거한다")
    void timeout_removesConnection() {
        EmitterProbe emitter = connect(1L);

        emitter.timeout().run();
        hub.sendHeartbeats();

        assertThat(emitter.sentData()).isEmpty();
    }

    @Test
    @DisplayName("연결 오류가 발생하면 heartbeat 대상에서 제거한다")
    void error_removesConnection() {
        EmitterProbe emitter = connect(1L);

        emitter.error().accept(new IllegalStateException("disconnected"));
        hub.sendHeartbeats();

        assertThat(emitter.sentData()).isEmpty();
    }

    @Test
    @DisplayName("이벤트 전송 중 IOException이 발생한 연결을 정리한다")
    void publish_removesConnectionWhenSendFails() {
        EmitterProbe emitter = connect(1L, new IOException("disconnected"));

        hub.send(lastConnectionId, 1L, notification());
        emitter.awaitSend();
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.timeout(5000)).disconnect(lastConnectionId);
        int attemptedDataCount = emitter.sentData().size();
        hub.sendHeartbeats();

        assertThat(attemptedDataCount).isPositive();
        assertThat(emitter.sentData()).hasSize(attemptedDataCount);
    }

    @Test
    @DisplayName("heartbeat는 연결된 스트림에 keepalive 주석을 전송한다")
    void sendHeartbeats_sendsKeepaliveComment() {
        EmitterProbe emitter = connect(1L);

        hub.sendHeartbeats();
        emitter.awaitSend();

        assertThat(emitter.sentData()).anySatisfy(item ->
                assertThat(((DataWithMediaType) item).getData()).asString().contains("keepalive")
        );
    }

    @Test
    @DisplayName("heartbeat 조회 도중 등록한 새 연결을 이전 조회 결과로 종료하지 않는다")
    void heartbeat_preservesConnectionRegisteredDuringRenewal() {
        org.mockito.Mockito.when(registry.renew(hub.nodeId())).thenAnswer(ignored -> {
            hub.connect(1L);
            return Set.of();
        });
        hub.sendHeartbeats();
        assertThat(hub.hasConnections()).isTrue();
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                .disconnect(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("느린 연결의 쓰기가 막혀도 다른 연결 전송과 임대 갱신은 계속된다")
    void slowConnection_doesNotBlockOtherConnectionsOrRenewal() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        connect(1L, null, started, release);
        String slow = lastConnectionId;
        var fast = connect(2L);
        String fastId = lastConnectionId;
        try {
            assertThat(hub.send(slow, 1L, notification())).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.send(slow, 2L, notification())).isFalse();
            assertThat(hub.send(fastId, 3L, notification())).isTrue();
            fast.awaitSend();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), hub::sendHeartbeats);
            org.mockito.Mockito.verify(registry).renew(hub.nodeId());
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("전송 풀 4개와 대기열 100개가 차도 호출 스레드에서 전송하지 않고 임대를 갱신한다")
    void saturatedPool_rejectsWithoutCallerRunsOrUnboundedGrowth() throws Exception {
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try {
            for (long i = 1; i <= 4; i++) {
                connect(i, null, started, release);
                assertThat(hub.send(lastConnectionId, i, notification())).isTrue();
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (long i = 5; i <= 104; i++) {
                connect(i);
                assertThat(hub.send(lastConnectionId, i, notification())).isTrue();
            }
            connect(105L);
            String rejected = lastConnectionId;
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                assertThat(hub.send(rejected, 105L, notification())).isFalse();
                hub.sendHeartbeats();
            });
            var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(hub, "sender");
            assertThat(pool.getLargestPoolSize()).isEqualTo(4);
            assertThat(pool.getQueue()).hasSize(100);
            org.mockito.Mockito.verify(registry).renew(hub.nodeId());
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    private EmitterProbe connect(Long recipientId) {
        return connect(recipientId, null);
    }

    private EmitterProbe connect(Long recipientId, IOException sendFailure) {
        return connect(recipientId, sendFailure, null, null);
    }

    private EmitterProbe connect(Long recipientId, IOException sendFailure,
                                 CountDownLatch started, CountDownLatch release) {
        SseEmitter emitter = hub.connect(recipientId);
        var ids = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.atLeastOnce()).register(ids.capture(),
                org.mockito.ArgumentMatchers.eq(hub.nodeId()), org.mockito.ArgumentMatchers.anyLong());
        lastConnectionId = ids.getValue();
        org.mockito.Mockito.when(registry.renew(hub.nodeId())).thenAnswer(ignored ->
                new java.util.HashSet<>(ids.getAllValues()));
        List<Object> sentData = new CopyOnWriteArrayList<>();
        List<Object> initialData = new CopyOnWriteArrayList<>();
        CountDownLatch sent = new CountDownLatch(1);
        Map<String, Object> callbacks = new HashMap<>();
        Class<?> handlerType = handlerType();
        Object handler = Proxy.newProxyInstance(
                handlerType.getClassLoader(),
                new Class<?>[] {handlerType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("send")) {
                        if (arguments[0] instanceof Set<?> items && items.stream().anyMatch(item ->
                                ((DataWithMediaType) item).getData().equals("event:connected\ndata:"))) {
                            initialData.addAll(items);
                            return null;
                        }
                        if (started != null) {
                            started.countDown();
                            release.await(10, TimeUnit.SECONDS);
                        }
                        Object value = arguments[0];
                        if (value instanceof Set<?> items) {
                            sentData.addAll(items);
                        } else {
                            sentData.add(value);
                        }
                        sent.countDown();
                        if (sendFailure != null) {
                            throw sendFailure;
                        }
                    } else if (method.getName().startsWith("on")) {
                        callbacks.put(method.getName(), arguments[0]);
                    }
                    return null;
                }
        );
        ReflectionTestUtils.invokeMethod(emitter, "initialize", handler);
        return new EmitterProbe(sentData, callbacks, sent, initialData);
    }

    private Class<?> handlerType() {
        try {
            return Class.forName(ResponseBodyEmitter.class.getName() + "$Handler");
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }

    private NotificationListItem notification() {
        return new NotificationListItem(
                10L,
                NotificationType.GIFT_RECEIVED,
                "선물이 도착했어요",
                "새 선물이 도착했습니다.",
                NotificationReferenceType.GIFT,
                501L,
                LocalDateTime.now(),
                false
        );
    }

    private record EmitterProbe(List<Object> sentData, Map<String, Object> callbacks, CountDownLatch sent,
                                List<Object> initialData) {
        private void awaitSend() {
            try {
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        private Runnable timeout() {
            return (Runnable) callbacks.get("onTimeout");
        }

        @SuppressWarnings("unchecked")
        private Consumer<Throwable> error() {
            return (Consumer<Throwable>) callbacks.get("onError");
        }

        private Runnable completion() {
            return (Runnable) callbacks.get("onCompletion");
        }
    }
}
