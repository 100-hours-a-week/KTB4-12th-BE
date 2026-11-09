package com.gift.gift.infrastructure.ai.profiling.amqp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProfilingResultListenerTest {

    private final SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final ProfilingResultTopologyPreflight preflight = mock(ProfilingResultTopologyPreflight.class);
    private final ProfilingResultListener listener = new ProfilingResultListener(container, admin, preflight);

    @Test
    @DisplayName("선언·유효 구성 검증 이후에만 Container를 시작한다")
    void declaresAndVerifiesBeforeStarting() {
        listener.start();
        var order = inOrder(admin, preflight, container);
        order.verify(admin).initialize();
        order.verify(preflight).verify();
        order.verify(container).start();
    }

    @Test
    @DisplayName("검증 실패는 구독을 시작하지 않고 중지 상태를 유지한다")
    void preflightFailurePreventsConsumptionAndRestart() {
        doThrow(new IllegalStateException("test-missing-policy")).when(preflight).verify();
        assertThatThrownBy(listener::start).isInstanceOf(IllegalStateException.class);
        verify(container, never()).start();
        verify(container).stop(any(Runnable.class));
        assertThat(listener.isHalted()).isTrue();
        assertThatThrownBy(listener::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("오류 중지는 콜백 방식으로 한 번만 요청해 Listener 자기 스레드 대기를 피한다")
    void haltsAsynchronouslyOnlyOnce() {
        listener.halt("DB_ERROR");
        listener.halt("DB_ERROR");
        verify(container).stop(any(Runnable.class));
        verify(container, never()).stop();
        assertThat(listener.isHalted()).isTrue();
    }

    @Test
    @DisplayName("다른 Container의 실패는 무시하고 자기 Consumer 실패에서만 중지한다")
    void onlyHaltsForOwnContainerFailure() {
        listener.onApplicationEvent(new ListenerContainerConsumerFailedEvent(new Object(), "other", null, false));
        assertThat(listener.isHalted()).isFalse();
        listener.onApplicationEvent(new ListenerContainerConsumerFailedEvent(container, "connection", null, false));
        assertThat(listener.isHalted()).isTrue();
        verify(container).stop(any(Runnable.class));
    }

    @Test
    @DisplayName("중지 요청 오류가 발생해도 halted 게이트를 유지하고 메시지를 추가 소비하지 않는다")
    void stopFailureDoesNotClearHaltGate() {
        doThrow(new IllegalStateException("test-stop-failure")).when(container).stop(any(Runnable.class));
        listener.halt("DB_ERROR");
        assertThat(listener.isHalted()).isTrue();
        assertThatThrownBy(listener::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정상 종료는 Container 종료 콜백과 destroy를 위임한다")
    void delegatesGracefulShutdownAndDestruction() {
        Runnable callback = mock(Runnable.class);
        listener.stop(callback);
        verify(container).stop(callback);
        listener.destroy();
        verify(container).destroy();
    }
}
