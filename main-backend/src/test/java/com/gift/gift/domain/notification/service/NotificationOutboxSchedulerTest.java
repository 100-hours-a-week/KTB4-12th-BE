package com.gift.gift.domain.notification.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class NotificationOutboxSchedulerTest {
    @Test
    @DisplayName("폴링 작업은 요청 스레드와 분리된 전용 스케줄러 스레드에서 실행된다")
    void pollUsesDedicatedSchedulerThread() throws Exception {
        var dispatcher = mock(NotificationOutboxDispatcher.class);
        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        doAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            dispatched.countDown();
            return null;
        }).when(dispatcher).dispatch();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NotificationOutboxDispatcher.class, () -> dispatcher);
            var unrelated = new UnrelatedScheduledTask();
            context.registerBean(UnrelatedScheduledTask.class, () -> unrelated);
            context.register(Scheduling.class, NotificationOutboxScheduler.class);
            context.refresh();
            assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("notification-outbox-");
            assertThat(unrelated.executed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(unrelated.threadName.get()).doesNotStartWith("notification-outbox-");
        }
    }

    static class UnrelatedScheduledTask {
        private final CountDownLatch executed = new CountDownLatch(1);
        private final AtomicReference<String> threadName = new AtomicReference<>();

        @Scheduled(fixedDelay = 1000)
        public void run() {
            threadName.set(Thread.currentThread().getName());
            executed.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }
}
