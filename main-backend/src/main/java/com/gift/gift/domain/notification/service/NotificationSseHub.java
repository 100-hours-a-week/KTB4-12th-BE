package com.gift.gift.domain.notification.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;

@Component
@Slf4j
public class NotificationSseHub implements DisposableBean {
    private static final int CONNECTION_QUEUE_CAPACITY = 100;
    private static final long CONNECTION_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final RedisMessageListenerContainer subscriptions;
    private final Object subscriptionMonitor = new Object();
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final Map<Long, RecipientConnections> recipients = new ConcurrentHashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
            Thread.ofPlatform().daemon(true).name("notification-sse-send-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public NotificationSseHub(
            @Qualifier("notificationRedisContainer") RedisMessageListenerContainer subscriptions,
            ObjectMapper mapper, MeterRegistry metrics) {
        this.subscriptions = subscriptions;
        this.mapper = mapper;
        this.metrics = metrics;
        metrics.gauge("notification.sse.connections", connections, Map::size);
        metrics.gauge("notification.redis.user.channels", recipients, Map::size);
    }

    public boolean hasConnections() {
        return !connections.isEmpty();
    }

    public SseEmitter connect(Long recipientId) {
        if (closed.get()) throw new IllegalStateException("SSE 서버가 종료 중입니다.");
        var connection = new Connection(recipientId);
        connection.emitter.onCompletion(() -> remove(connection));
        connection.emitter.onTimeout(() -> close(connection));
        connection.emitter.onError(ignored -> remove(connection));
        try {
            connection.emitter.send(SseEmitter.event().name("connected").data("connected"));
        } catch (IOException failure) {
            connection.emitter.completeWithError(failure);
            throw new UncheckedIOException(failure);
        }
        try {
            recipients.compute(recipientId, (id, existing) -> {
                if (closed.get()) throw new IllegalStateException("SSE 서버가 종료 중입니다.");
                if (connection.terminated.get()) return existing;
                var group = existing == null ? new RecipientConnections(id) : existing;
                connections.put(connection.id, connection);
                group.members.put(connection.id, connection);
                if (existing == null) {
                    synchronized (subscriptionMonitor) {
                        try {
                            subscriptions.addMessageListener(group, group.topic);
                        } catch (RuntimeException failure) {
                            group.active.set(false);
                            group.members.remove(connection.id);
                            connections.remove(connection.id, connection);
                            metrics.counter("notification.redis.subscription.failure").increment();
                            try {
                                subscriptions.removeMessageListener(group, group.topic);
                            } catch (RuntimeException cleanupFailure) {
                                failure.addSuppressed(cleanupFailure);
                            }
                            throw failure;
                        }
                    }
                }
                return group;
            });
        } catch (RuntimeException failure) {
            connection.emitter.completeWithError(failure);
            throw failure;
        }
        return connection.emitter;
    }

    private boolean offer(Connection connection, SseEmitter.SseEventBuilder event, String eventType) {
        synchronized (connection) {
            if (connection.terminated.get()) return false;
            boolean accepted = true;
            if (eventType.equals("notification")) {
                if (connection.pendingNotifications == CONNECTION_QUEUE_CAPACITY) {
                    enqueueResync(connection);
                    metrics.counter("notification.sse.dropped", "reason", "connection_queue_full",
                            "event", eventType).increment();
                    accepted = false;
                } else {
                    connection.pending.addLast(new PendingEvent(event, eventType));
                    connection.pendingNotifications++;
                }
            } else if (eventType.equals("resync")) {
                enqueueResync(connection);
            } else if (eventType.equals("heartbeat") && !connection.sending.get()
                    && connection.pending.isEmpty() && !connection.resync.get()) {
                connection.pending.addLast(new PendingEvent(event, eventType));
            }
            schedule(connection);
            return accepted;
        }
    }

    private void enqueueResync(Connection connection) {
        if (connection.resync.compareAndSet(false, true)) {
            connection.pending.addLast(new PendingEvent(SseEmitter.event().name("resync").data("resync"), "resync"));
        }
    }

    private void schedule(Connection connection) {
        if (connection.terminated.get() || connection.sending.get()
                || connection.pending.isEmpty()) return;
        connection.sending.set(true);
        try {
            sender.execute(() -> drain(connection));
        } catch (RejectedExecutionException full) {
            connection.sending.set(false);
            metrics.counter("notification.sse.schedule.rejected").increment();
        }
    }

    private void drain(Connection connection) {
        String eventType = "resync";
        try {
            for (int sent = 0; sent < CONNECTION_QUEUE_CAPACITY; sent++) {
                PendingEvent next;
                synchronized (connection) {
                    if (connection.terminated.get()) return;
                    next = connection.pending.pollFirst();
                    if (next == null) return;
                    if (next.type().equals("notification")) connection.pendingNotifications--;
                    if (next.type().equals("resync")) connection.resync.set(false);
                }
                eventType = next.type();
                connection.emitter.send(next.event());
                if (eventType.equals("resync")) {
                    metrics.counter("notification.sse.resync").increment();
                }
                metrics.counter("notification.sse.write", "result", "success", "event", eventType).increment();
            }
        } catch (IOException | IllegalStateException failure) {
            metrics.counter("notification.sse.write", "result", "failure", "event", eventType).increment();
            remove(connection);
        } finally {
            synchronized (connection) {
                connection.sending.set(false);
                schedule(connection);
            }
            if (connection.terminated.get()) connection.emitter.complete();
        }
    }

    public void sendHeartbeats() {
        connections.values().forEach(
                connection -> offer(connection, SseEmitter.event().comment("keepalive"), "heartbeat"));
    }

    public void closeAll() {
        connections.values().forEach(this::close);
    }

    private void close(Connection connection) {
        remove(connection);
        if (connection.sending.get()) return;
        try {
            sender.execute(connection.emitter::complete);
        } catch (RejectedExecutionException full) {
            log.warn("SSE 연결 종료 대기: 전송 풀 포화 또는 종료");
        }
    }

    private void remove(Connection connection) {
        synchronized (connection) {
            connection.terminated.set(true);
            connection.pending.clear();
            connection.pendingNotifications = 0;
            connection.resync.set(false);
        }
        recipients.computeIfPresent(connection.recipientId, (id, group) -> {
            if (!connections.remove(connection.id, connection)) return group;
            group.members.remove(connection.id);
            if (!group.members.isEmpty()) return group;
            group.active.set(false);
            try {
                synchronized (subscriptionMonitor) {
                    subscriptions.removeMessageListener(group, group.topic);
                }
            } catch (RuntimeException failure) {
                metrics.counter("notification.redis.subscription.failure").increment();
                log.warn("알림 Redis 구독 해제 실패: errorType={}", failure.getClass().getName());
            }
            return null;
        });
    }

    @Override
    public void destroy() {
        closed.set(true);
        closeAll();
        sender.shutdownNow();
    }

    private class RecipientConnections implements MessageListener, SubscriptionListener {
        private final Long recipientId;
        private final ChannelTopic topic;
        private final Map<String, Connection> members = new ConcurrentHashMap<>();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private RecipientConnections(Long recipientId) {
            this.recipientId = recipientId;
            this.topic = new ChannelTopic(NotificationRedisPublisher.channel(recipientId));
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            if (!active.get()) return;
            try {
                var event = mapper.readValue(message.getBody(), NotificationCreatedEvent.class);
                if (!topic.getTopic().equals(new String(message.getChannel(), StandardCharsets.UTF_8))
                        || !recipientId.equals(event.recipientId()) || event.notification() == null
                        || event.notification().notificationId() == null) {
                    metrics.counter("notification.redis.received", "result", "invalid").increment();
                    return;
                }
                metrics.counter("notification.redis.received", "result", "valid").increment();
                NotificationListItem notification = event.notification();
                members.values().forEach(connection -> offer(connection, SseEmitter.event()
                                .id(notification.notificationId().toString()).name("notification").data(notification),
                        "notification"));
            } catch (RuntimeException failure) {
                metrics.counter("notification.redis.received", "result", "invalid").increment();
                log.warn("알림 Redis 메시지 처리 실패: errorType={}", failure.getClass().getName());
            }
        }

        @Override
        public void onChannelSubscribed(byte[] channel, long count) {
            if (!active.get() || !topic.getTopic().equals(new String(channel, StandardCharsets.UTF_8))) return;
            metrics.counter("notification.redis.subscribed").increment();
            members.values().forEach(connection ->
                    offer(connection, SseEmitter.event().comment("subscribed"), "resync"));
        }
    }

    private record PendingEvent(SseEmitter.SseEventBuilder event, String type) {}

    private static class Connection {
        private final String id = UUID.randomUUID().toString();
        private final Long recipientId;
        private final SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
        private final AtomicBoolean terminated = new AtomicBoolean();
        private int pendingNotifications;
        private final ArrayDeque<PendingEvent> pending = new ArrayDeque<>();
        private final AtomicBoolean sending = new AtomicBoolean();
        private final AtomicBoolean resync = new AtomicBoolean();

        private Connection(Long recipientId) {
            this.recipientId = recipientId;
        }
    }
}
