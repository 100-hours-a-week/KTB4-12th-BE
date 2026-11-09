package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

@Slf4j
@RequiredArgsConstructor
public class ProfilingResultListener implements
        SmartLifecycle,
        DisposableBean,
        ApplicationListener<ListenerContainerConsumerFailedEvent> {

    private final SimpleMessageListenerContainer container;
    private final RabbitAdmin admin;
    private final ProfilingResultTopologyPreflight preflight;

    private final AtomicBoolean halted = new AtomicBoolean();

    @Override
    public void start() {
        if (container.isRunning()) {
            return;
        }

        Assert.state(!halted.get(), "오류로 중단된 프로파일링 결과 Consumer는 재시작할 수 없습니다.");

        try {
            // SmartLifecycle은 ApplicationRunner보다 먼저 시작된다.
            admin.initialize();
            preflight.verify();
            container.start();
        } catch (RuntimeException exception) {
            halt("START_FAILURE");
            throw exception;
        }
    }

    public void halt(String reason) {
        if (!halted.compareAndSet(false, true)) {
            return;
        }

        log.error("PROFILING_RESULT_CONSUMER_HALTED reason={}", reason);

        try {
            // 콜백 방식은 현재 Listener 스레드의 종료를 기다리지 않는다.
            container.stop(() -> log.info("PROFILING_RESULT_CONSUMER_STOPPED"));
        } catch (RuntimeException exception) {
            log.error(
                    "PROFILING_RESULT_CONSUMER_STOP_FAILURE exceptionType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    public boolean isHalted() {
        return halted.get();
    }

    @Override
    public void onApplicationEvent(ListenerContainerConsumerFailedEvent event) {
        if (event.getSource() == container) {
            halt("BROKER_CONSUMER_FAILURE");
        }
    }

    @Override
    public void stop() {
        container.stop();
    }

    @Override
    public void stop(Runnable callback) {
        container.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return container.isRunning();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void destroy() {
        container.destroy();
    }
}
