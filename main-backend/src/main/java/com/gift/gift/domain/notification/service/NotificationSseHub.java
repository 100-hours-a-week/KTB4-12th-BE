package com.gift.gift.domain.notification.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
            connectionService.register(connectionId, nodeId, recipientId);
        } catch (RuntimeException failure) {
            emitter.completeWithError(failure);
            throw failure;
        }
        connections.put(connectionId, emitter);
        return emitter;
    }

    public boolean send(String connectionId, Long deliveryId, NotificationListItem notification) {
        SseEmitter emitter = connections.get(connectionId);
        if (emitter == null) return false;
        try {
            emitter.send(SseEmitter.event().id(deliveryId.toString()).name("notification").data(notification));
            return true;
        } catch (IOException | IllegalStateException failure) {
            close(connectionId, emitter);
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
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException failure) {
                    close(id, emitter);
                }
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
        emitter.complete();
    }

    @Override
    public void destroy() {
        closeAll();
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
