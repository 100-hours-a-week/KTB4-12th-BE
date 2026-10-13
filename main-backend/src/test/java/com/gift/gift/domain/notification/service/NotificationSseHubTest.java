package com.gift.gift.domain.notification.service;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    private final NotificationSseHub hub = new NotificationSseHub();

    @Test
    @DisplayName("알림은 해당 수신자의 SSE 연결에만 전달한다")
    void publish_sendsOnlyToRecipientConnections() {
        EmitterProbe recipient = connect(1L);
        EmitterProbe anotherRecipient = connect(2L);
        NotificationListItem notification = notification();

        hub.publish(1L, notification);

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

        hub.publish(1L, notification());
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

        assertThat(emitter.sentData()).anySatisfy(item ->
                assertThat(((DataWithMediaType) item).getData()).asString().contains("keepalive")
        );
    }

    private EmitterProbe connect(Long recipientId) {
        return connect(recipientId, null);
    }

    private EmitterProbe connect(Long recipientId, IOException sendFailure) {
        SseEmitter emitter = hub.connect(recipientId);
        List<Object> sentData = new ArrayList<>();
        Map<String, Object> callbacks = new HashMap<>();
        Class<?> handlerType = handlerType();
        Object handler = Proxy.newProxyInstance(
                handlerType.getClassLoader(),
                new Class<?>[] {handlerType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("send")) {
                        Object sent = arguments[0];
                        if (sent instanceof Set<?> items) {
                            sentData.addAll(items);
                        } else {
                            sentData.add(sent);
                        }
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
        return new EmitterProbe(sentData, callbacks);
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

    private record EmitterProbe(List<Object> sentData, Map<String, Object> callbacks) {

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
