package com.gift.gift.domain.notification.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;

@Component
public class NotificationSseHub {

    private static final long CONNECTION_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final Map<Long, Set<SseEmitter>> connections = new ConcurrentHashMap<>();

    public SseEmitter connect(Long recipientId) {
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
        connections.computeIfAbsent(recipientId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);

        Runnable remove = () -> remove(recipientId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(ignored -> remove.run());
        return emitter;
    }

    public void publish(Long recipientId, NotificationListItem notification) {
        Set<SseEmitter> emitters = connections.get(recipientId);
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .data(notification));
            } catch (IOException | IllegalStateException exception) {
                remove(recipientId, emitter);
                emitter.completeWithError(exception);
            }
        }
    }

    @Scheduled(fixedRate = 15000)
    public void sendHeartbeats() {
        connections.forEach((recipientId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException exception) {
                    remove(recipientId, emitter);
                    emitter.completeWithError(exception);
                }
            }
        });
    }

    private void remove(Long recipientId, SseEmitter emitter) {
        connections.computeIfPresent(recipientId, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
