package com.gift.gift.domain.notification.service;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;

import static org.awaitility.Awaitility.await;

class SseEmitterProbe {
    final List<Object> data = new CopyOnWriteArrayList<>();
    final Map<String, Object> callbacks = new ConcurrentHashMap<>();

    SseEmitterProbe(SseEmitter emitter) {
        this(emitter, () -> {
        });
    }

    SseEmitterProbe(SseEmitter emitter, ThrowingAction beforeNotification) {
        try {
            Class<?> type = Class.forName(ResponseBodyEmitter.class.getName() + "$Handler");
            var handler = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().equals("send") && args[0] instanceof Set<?> items) {
                    for (Object item : items) {
                        Object value = ((DataWithMediaType) item).getData();
                        if (value instanceof NotificationListItem) beforeNotification.run();
                        data.add(value);
                    }
                } else if (method.getName().startsWith("on")) {
                    callbacks.put(method.getName(), args[0]);
                }
                return null;
            });
            ReflectionTestUtils.invokeMethod(emitter, "initialize", handler);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    List<NotificationListItem> notifications() {
        return data.stream().filter(NotificationListItem.class::isInstance).map(
                NotificationListItem.class::cast).toList();
    }

    void awaitNotifications(int size) {
        await().atMost(Duration.ofSeconds(10)).until(() -> notifications().size() == size);
    }

    void complete() {
        ((Runnable) callbacks.get("onCompletion")).run();
    }

    void timeout() {
        ((Runnable) callbacks.get("onTimeout")).run();
    }

    @SuppressWarnings("unchecked")
    void error() {
        ((Consumer<Throwable>) callbacks.get("onError")).accept(new java.io.IOException("closed"));
    }

    @FunctionalInterface
    interface ThrowingAction {
        void run() throws Exception;
    }
}
