package com.gift.gift.infrastructure.ai.profiling.amqp;

import com.rabbitmq.client.BlockedListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProfilingPublisherStateTest {
    @Test
    @DisplayName("브로커 차단·해제를 반영하고 이전 연결의 늦은 이벤트는 무시한다")
    void blockedEvents_onlyAffectCurrentConnection() throws Exception {
        var state = new ProfilingPublisherState();
        Connection oldConnection = connection();
        state.onCreate(oldConnection);
        var listener = ArgumentCaptor.forClass(BlockedListener.class);
        verify(oldConnection.getDelegate()).addBlockedListener(listener.capture());
        listener.getValue().handleBlocked("memory alarm");
        assertThat(state.unavailable()).isTrue();
        long blockedEpoch = state.epoch();
        listener.getValue().handleUnblocked();
        assertThat(state.unavailable()).isFalse();
        assertThat(state.epoch()).isGreaterThan(blockedEpoch);

        Connection replacement = connection();
        state.onCreate(replacement);
        long replacementEpoch = state.epoch();
        listener.getValue().handleBlocked("late event");
        state.onClose(oldConnection);
        assertThat(state.unavailable()).isFalse();
        assertThat(state.epoch()).isEqualTo(replacementEpoch);
        state.onClose(replacement);
        assertThat(state.epoch()).isGreaterThan(replacementEpoch);
    }

    @Test
    @DisplayName("종료 상태는 연결 재생성이나 차단 해제로 취소되지 않는다")
    void shutdown_remainsUnavailableAfterReconnect() {
        var state = new ProfilingPublisherState();
        state.beginShutdown();
        state.onCreate(connection());
        assertThat(state.unavailable()).isTrue();
    }

    private Connection connection() {
        Connection connection = mock(Connection.class);
        when(connection.getDelegate()).thenReturn(mock(com.rabbitmq.client.Connection.class));
        return connection;
    }
}
