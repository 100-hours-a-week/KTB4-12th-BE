package com.gift.gift.domain.notification.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSseHub implements DisposableBean {
    private static final long CONNECTION_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final NotificationSseConnectionService connectionService;
    private final String nodeId = UUID.randomUUID().toString();
    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();

    private final Set<String> sending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
            Thread.ofPlatform().daemon(true).name("notification-sse-send-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public String nodeId() {
        return nodeId;
    }

    public boolean hasConnections() {
        return !connections.isEmpty();
    }

    public SseEmitter connect(Long recipientId) {
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
        Runnable remove = () -> remove(connectionId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> close(connectionId, emitter));
        emitter.onError(ignored -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("connected"));
            connectionService.register(connectionId, nodeId, recipientId);
        } catch (IOException failure) {
            emitter.completeWithError(failure);
            throw new UncheckedIOException(failure);
        } catch (RuntimeException failure) {
            emitter.completeWithError(failure);
            throw failure;
        }
        connections.put(connectionId, emitter);
        return emitter;
    }

    public boolean send(String connectionId, Long deliveryId, NotificationListItem notification) {
        SseEmitter emitter = connections.get(connectionId);
        return emitter != null && offer(connectionId, emitter, SseEmitter.event()
                .id(deliveryId.toString()).name("notification").data(notification));
    }

    private boolean offer(String connectionId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        if (!sending.add(connectionId)) return false;
        try {
            sender.execute(() -> {
                try {
                    if (connections.get(connectionId) == emitter) emitter.send(event);
                } catch (IOException | IllegalStateException failure) {
                    remove(connectionId, emitter);
                } finally {
                    sending.remove(connectionId);
                    if (connections.get(connectionId) != emitter) emitter.complete();
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            // 호출 스레드에서 대신 전송하지 않는다. 미확인 전달 작업은 DB에서 다시 조회한다.
            sending.remove(connectionId);
            return false;
        }
    }

    public void sendHeartbeats() {
        // 갱신 조회 중 새로 등록된 연결을 오래된 조회 결과로 종료하지 않는다.
        var snapshot = Map.copyOf(connections);
        try {
            var active = connectionService.renew(nodeId);
            snapshot.forEach((id, emitter) -> {
                if (!active.contains(id)) {
                    close(id, emitter);
                    return;
                }
                offer(id, emitter, SseEmitter.event().comment("keepalive"));
            });
        } catch (RuntimeException failure) {
            // DB에서 연결 등록을 유지하지 못하면 조용히 누락시키지 않고 재연결을 유도한다.
            log.warn("SSE 연결 임대 갱신 실패: errorType={}", failure.getClass().getName());
            closeAll();
        }
    }

    public void closeAll() {
        connections.forEach(this::close);
    }

    private void close(String connectionId, SseEmitter emitter) {
        remove(connectionId, emitter);
        if (sending.contains(connectionId)) return;
        try {
            sender.execute(emitter::complete);
        } catch (RejectedExecutionException full) {
            // shortcut: 풀 포화 시 종료는 Servlet timeout에 의존한다. 즉시 종료가 필요하면 별도 종료 경로를 둔다.
            log.warn("SSE 연결 종료 대기: 전송 풀 포화 또는 종료");
        }
    }

    @Override
    public void destroy() {
        closeAll();
        sender.shutdownNow();
    }

    private void remove(String connectionId, SseEmitter emitter) {
        if (!connections.remove(connectionId, emitter)) return;
        try {
            connectionService.disconnect(connectionId);
        } catch (RuntimeException failure) {
            log.warn("SSE 연결 정리 대기: errorType={}", failure.getClass().getName());
        }
    }
}
