package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.AmqpIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import static com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingResultListener.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProfilingResultListenerTest {
    private final SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final Runnable probe = mock(Runnable.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final Deque<Runnable> checks = new ArrayDeque<>();
    private final ProfilingResultListener listener = new ProfilingResultListener(
            container, admin, probe, executor, () -> 30000L, new SimpleMeterRegistry(), Clock.systemUTC(), 1);

    @BeforeEach
    void controlTasksWithoutWaiting() {
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        doAnswer(call -> {
            checks.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(executor).schedule(any(Runnable.class), eq(30000L), eq(TimeUnit.MILLISECONDS));
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(container).stop(any(Runnable.class));
    }

    @Test
    @DisplayName("선언·점검 이후에만 소비를 시작한다")
    void verifiesBeforeStarting() {
        listener.start();
        var order = inOrder(admin, probe, container);
        order.verify(admin).initialize();
        order.verify(probe).run();
        order.verify(container).start();
    }

    @Test
    @DisplayName("구성 검증 실패는 소비와 자동 재개를 차단한다")
    void blocksInvalidConfiguration() {
        doThrow(new IllegalStateException("missing policy")).when(probe).run();
        listener.start();
        verify(container, never()).start();
        assertThat(listener.status()).contains("reason=CONFIGURATION");
        assertThat(checks).isEmpty();
    }

    @Test
    @DisplayName("일시 장애는 연속 정상 점검 2회 이후에만 자동 재개한다")
    void requiresTwoHealthyChecks() {
        listener.start();
        listener.halt(TRANSIENT_DB);
        checks.removeFirst().run();
        verify(container).start();
        checks.removeFirst().run();
        verify(container, times(2)).start();
    }

    @Test
    @DisplayName("중간 점검 실패는 연속 정상 횟수를 초기화한다")
    void resetsHealthyStreakOnFailure() {
        listener.start();
        listener.halt(TRANSIENT_DB);
        checks.removeFirst().run();
        doThrow(new AmqpConnectException(new java.net.ConnectException("offline"))).doNothing().when(probe).run();
        checks.removeFirst().run();
        checks.removeFirst().run();
        verify(container).start();
        checks.removeFirst().run();
        verify(container, times(2)).start();
    }

    @ParameterizedTest
    @EnumSource(value = ProfilingResultListener.Reason.class,
            names = {"MANUAL", "CONFIGURATION", "UNCLASSIFIED"})
    @DisplayName("수동·구성·미분류 중지는 자동 재개하지 않는다")
    void doesNotScheduleUnsafeResume(ProfilingResultListener.Reason reason) {
        listener.start();
        listener.halt(reason);
        assertThat(checks).isEmpty();
        assertThat(listener.isHalted()).isTrue();
    }

    @Test
    @DisplayName("진행 중인 점검이 성공해도 수동 중지가 우선한다")
    void manualPauseWinsDuringProbe() {
        listener.start();
        listener.halt(TRANSIENT_DB);
        checks.removeFirst().run();
        doAnswer(call -> { listener.pause(); return null; }).when(probe).run();
        checks.removeFirst().run();
        assertThat(listener.status()).contains("reason=MANUAL");
        verify(container).start();
        assertThat(checks).isEmpty();
    }

    @Test
    @DisplayName("처리 성공 없이 반복되는 자동 재개는 설정 상한에서 끝난다")
    void boundsAutomaticResume() {
        listener.start();
        listener.halt(TRANSIENT_DB);
        checks.removeFirst().run();
        checks.removeFirst().run();
        listener.halt(TRANSIENT_DB);
        checks.removeFirst().run();
        checks.removeFirst().run();
        assertThat(listener.status()).contains("reason=AUTO_RESUME_LIMIT");
        verify(container, times(2)).start();
        assertThat(checks).isEmpty();
    }

    @Test
    @DisplayName("중지 요청 실패 시 게이트를 유지하고 자동 재개하지 않는다")
    void stopFailureKeepsGateClosed() {
        listener.start();
        doThrow(new IllegalStateException("stop failed")).when(container).stop(any(Runnable.class));
        listener.halt(TRANSIENT_DB);
        assertThat(listener.status()).contains("reason=STOP_FAILURE");
        assertThat(listener.isHalted()).isTrue();
        assertThat(checks).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"320,false", "320,true", "403,false", "403,true"})
    @DisplayName("직접·중첩된 브로커 강제 종료만 자동 복구하고 접근 거부는 구성 오류로 중지한다")
    void classifiesBrokerCloseCodes(int replyCode, boolean wrapped) {
        listener.start();
        var close = new AMQP.Connection.Close.Builder().replyCode(replyCode)
                .replyText("test close").classId(0).methodId(0).build();
        var shutdown = new ShutdownSignalException(true, false, close, null);
        RuntimeException failure = wrapped ? new AmqpIOException(new java.io.IOException(shutdown)) : shutdown;
        listener.onApplicationEvent(new ListenerContainerConsumerFailedEvent(container, "closed", failure, false));
        assertThat(listener.status()).contains("reason=" + (replyCode == 320 ? "BROKER" : "CONFIGURATION"));
        assertThat(checks).hasSize(replyCode == 320 ? 1 : 0);
    }

    @Test
    @DisplayName("다른 Container의 실패는 무시한다")
    void ignoresOtherContainerFailure() {
        listener.start();
        listener.onApplicationEvent(new ListenerContainerConsumerFailedEvent(new Object(), "other", null, false));
        assertThat(listener.isHalted()).isFalse();
        verify(container, never()).stop(any(Runnable.class));
    }

    @Test
    @DisplayName("수동 재개는 다시 검증하고 종료 후 남은 점검은 소비를 시작하지 않는다")
    void verifiesManualResumeAndCancelsShutdownTasks() {
        listener.start();
        listener.pause();
        listener.resume();
        verify(admin, times(2)).initialize();
        verify(probe, times(2)).run();
        listener.halt(TRANSIENT_DB);
        Runnable staleCheck = checks.removeFirst();
        listener.destroy();
        staleCheck.run();
        listener.start();
        verify(container, times(2)).start();
        verify(executor).shutdownNow();
        verify(container).destroy();
        assertThat(listener.isRunning()).isFalse();
    }
}
