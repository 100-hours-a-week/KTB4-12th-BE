package com.gift.gift.domain.bugreport.messaging;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.DisposableBean;

@lombok.extern.slf4j.Slf4j
public class ReportDiscordListener implements SmartLifecycle, DisposableBean {
    private final SimpleMessageListenerContainer container;
    private final ReportTopologyPreflight preflight;
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("report-discord-recovery").factory());
    private volatile boolean running;
    private boolean recovering;
    private long recoveryDelaySeconds = 5;

    public ReportDiscordListener(SimpleMessageListenerContainer container, ReportTopologyPreflight preflight) {
        this.container = container;
        this.preflight = preflight;
    }

    @Override
    public synchronized void start() {
        running = true;
        startContainer();
    }

    private synchronized void startContainer() {
        if (!running) return;
        try {
            preflight.verify();
            container.start();
            recovering = false;
        } catch (RuntimeException exception) {
            log.warn("Discord consumer topology/start unavailable; retrying without consuming");
            recover();
        }
    }

    public synchronized void recover() {
        if (!running || recovering) return;
        recovering = true;
        container.stop(() -> {
            synchronized (this) {
                if (!running) return;
                long delay = recoveryDelaySeconds;
                recoveryDelaySeconds = Math.min(60, delay * 2);
                recovery.schedule(() -> {
                    synchronized (this) {
                        recovering = false;
                        startContainer();
                    }
                }, delay, TimeUnit.SECONDS);
            }
        });
    }

    public synchronized void recovered() {
        recoveryDelaySeconds = 5;
    }

    @Override
    public void stop() {
        running = false;
        container.stop();
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        container.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void destroy() {
        running = false;
        recovery.shutdownNow();
        container.destroy();
    }
}
