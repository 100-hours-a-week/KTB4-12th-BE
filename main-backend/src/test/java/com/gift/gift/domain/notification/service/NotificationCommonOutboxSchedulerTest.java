package com.gift.gift.domain.notification.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationCommonOutboxSchedulerTest {
    @Test
    @DisplayName("기본 OFF와 전역 스케줄링 OFF에서는 공통 Worker 스레드를 시작하지 않는다")
    void disabledDoesNotStartWorker() {
        var dispatcher = mock(NotificationCommonOutboxDispatcher.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(NotificationCommonOutboxScheduler.class)
            .withBean(NotificationCommonOutboxDispatcher.class, () -> dispatcher);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(NotificationCommonOutboxScheduler.class));
        runner.withPropertyValues("app.notification.common-outbox.enabled=true", "app.scheduling.enabled=false")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(NotificationCommonOutboxScheduler.class));
        verify(dispatcher, never()).dispatch();
    }

    @Test
    @DisplayName("전환 ON은 별도 DB Worker 스레드로 실행하며 잘못된 폴링 간격은 거부한다")
    void enabledUsesDedicatedWorkerAndValidatesDelay() {
        var dispatcher = mock(NotificationCommonOutboxDispatcher.class);
        var entered = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        doAnswer(call -> { thread.set(Thread.currentThread().getName()); entered.countDown(); return null; })
            .when(dispatcher).dispatch();
        var runner = new ApplicationContextRunner().withUserConfiguration(NotificationCommonOutboxScheduler.class)
            .withBean(NotificationCommonOutboxDispatcher.class, () -> dispatcher)
            .withPropertyValues("app.notification.common-outbox.enabled=true");
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(NotificationCommonOutboxScheduler.class);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("notification-common-outbox-");
        });
        runner.withPropertyValues("app.notification.outbox.poll-delay-ms=0")
            .run(context -> assertThat(context).hasFailed());
    }
}
