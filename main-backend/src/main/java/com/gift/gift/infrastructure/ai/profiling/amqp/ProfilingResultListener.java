package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public class ProfilingResultListener implements
        SmartLifecycle,
        DisposableBean,
        ApplicationListener<ListenerContainerConsumerFailedEvent> {

    private static final Logger log =
            LoggerFactory.getLogger(ProfilingResultListener.class);

    private final SimpleMessageListenerContainer container;
    private final RabbitAdmin admin;
    private final Runnable probe;
    private final ScheduledExecutorService executor;
    private final LongSupplier recoveryDelay;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int automaticResumeLimit;

    private final Object gate = new Object();
    private final Object containerLifecycle = new Object();

    private State state = State.STOPPED;
    private Reason reason = Reason.NONE;
    private long generation;
    private long changedAt;
    private int healthyChecks;
    private int automaticResumes;
    private boolean stopping;
    private boolean active;
    private ScheduledFuture<?> pendingCheck;

    public ProfilingResultListener(
            SimpleMessageListenerContainer container,
            RabbitAdmin admin,
            Runnable probe,
            ScheduledExecutorService executor,
            LongSupplier recoveryDelay,
            MeterRegistry meters,
            Clock clock,
            int automaticResumeLimit
    ) {
        if (automaticResumeLimit < 1) {
            throw new IllegalArgumentException(
                    "자동 재개 상한은 1 이상이어야 합니다."
            );
        }

        this.container = container;
        this.admin = admin;
        this.probe = probe;
        this.executor = executor;
        this.recoveryDelay = recoveryDelay;
        this.meters = meters;
        this.clock = clock;
        this.automaticResumeLimit = automaticResumeLimit;

        meters.gauge(
                "profiling.result.consumer.running",
                this,
                listener -> !listener.isHalted()
                        && listener.container.isRunning() ? 1 : 0
        );
    }

    @Override
    public void start() {
        long token;

        synchronized (gate) {
            if (active || state == State.CLOSED) {
                return;
            }

            active = true;
            change(State.STARTING, Reason.NONE);
            token = generation;
        }

        submit(() -> startConsumer(token, "startup"));
    }

    public void halt(Reason failure) {
        boolean stopRequired;

        synchronized (gate) {
            if (!active
                    || state == State.CLOSED
                    || reason == Reason.MANUAL
                    || state == State.PAUSED
                    && !reason.automatic()
                    && failure != Reason.MANUAL) {
                return;
            }

            change(State.PAUSED, failure);
            healthyChecks = 0;
            cancelCheck();

            stopRequired = !stopping;
            stopping = true;
        }

        observe("stop", failure.name());

        if (stopRequired) {
            submit(() -> {
                try {
                    container.stop(() -> {
                        synchronized (gate) {
                            stopping = false;
                            scheduleCheck();
                        }
                    });
                } catch (RuntimeException exception) {
                    synchronized (gate) {
                        if (state != State.CLOSED) {
                            change(State.PAUSED, Reason.STOP_FAILURE);
                        }
                    }

                    log.error(
                            "PROFILING_RESULT_STOP_FAILED type={}",
                            exception.getClass().getSimpleName()
                    );
                }
            });
        }
    }

    public void pause() {
        halt(Reason.MANUAL);
    }

    public void resume() {
        long token;

        synchronized (gate) {
            if (!active || state != State.PAUSED || stopping) {
                throw new IllegalStateException(
                        "중지 정리가 끝난 실행 인스턴스에서만 재개할 수 있습니다."
                );
            }

            cancelCheck();
            healthyChecks = 0;
            automaticResumes = 0;

            change(State.STARTING, Reason.NONE);
            token = generation;
        }

        observe("resume_attempt", "manual");
        submit(() -> startConsumer(token, "manual"));
    }

    private void startConsumer(long token, String mode) {
        try {
            if (!current(token, State.STARTING)) {
                return;
            }

            if (!"automatic".equals(mode)) {
                admin.initialize();
                probe.run();
            }

            if (!current(token, State.STARTING)) {
                return;
            }

            synchronized (containerLifecycle) {
                if (!current(token, State.STARTING)) {
                    return;
                }

                container.start();
            }

            synchronized (gate) {
                if (generation == token && state == State.STARTING) {
                    change(State.RUNNING, Reason.NONE);
                    observe("resume_success", mode);
                }
            }
        } catch (RuntimeException exception) {
            if (current(token, State.STARTING)) {
                halt(infrastructureReason(exception));
            }
        }
    }

    private void checkRecovery(long token) {
        if (!current(token, State.PAUSED)) {
            return;
        }

        try {
            probe.run();
        } catch (RuntimeException exception) {
            synchronized (gate) {
                if (generation != token || state != State.PAUSED) {
                    return;
                }

                healthyChecks = 0;
                Reason failure = infrastructureReason(exception);

                if (!failure.automatic()) {
                    change(State.PAUSED, failure);
                }

                observe("probe", failure.name());
                scheduleCheck();
            }

            return;
        }

        synchronized (gate) {
            if (generation != token
                    || state != State.PAUSED
                    || !reason.automatic()) {
                return;
            }

            observe("probe", "healthy");

            if (++healthyChecks < 2) {
                scheduleCheck();
                return;
            }

            if (automaticResumes >= automaticResumeLimit) {
                change(State.PAUSED, Reason.AUTO_RESUME_LIMIT);
                observe("stop", Reason.AUTO_RESUME_LIMIT.name());
                return;
            }

            automaticResumes++;
            change(State.STARTING, Reason.NONE);
            token = generation;
        }

        observe("resume_attempt", "automatic");
        startConsumer(token, "automatic");
    }

    private void scheduleCheck() {
        if (!active
                || state != State.PAUSED
                || !reason.automatic()
                || stopping
                || executor.isShutdown()) {
            return;
        }

        cancelCheck();
        long token = generation;

        pendingCheck = executor.schedule(
                () -> checkRecovery(token),
                recoveryDelay.getAsLong(),
                TimeUnit.MILLISECONDS
        );
    }

    public boolean awaitRetry(long millis) throws InterruptedException {
        long deadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);

        synchronized (gate) {
            while (!isHalted()) {
                long remaining = deadline - System.nanoTime();

                if (remaining <= 0) {
                    return true;
                }

                TimeUnit.NANOSECONDS.timedWait(gate, remaining);
            }

            return false;
        }
    }

    public void settledSuccessfully() {
        synchronized (gate) {
            automaticResumes = 0;
        }
    }

    public boolean isHalted() {
        synchronized (gate) {
            return state != State.RUNNING && state != State.STARTING;
        }
    }

    public String status() {
        synchronized (gate) {
            return "state=" + state
                    + ",reason=" + reason
                    + ",changedAt=" + changedAt
                    + ",healthyChecks=" + healthyChecks
                    + ",stopping=" + stopping
                    + ",automaticResumes=" + automaticResumes
                    + ",consumerRunning=" + container.isRunning();
        }
    }

    private boolean current(long token, State expected) {
        synchronized (gate) {
            return active && generation == token && state == expected;
        }
    }

    private void change(State next, Reason cause) {
        state = next;
        reason = cause;
        changedAt = clock.millis();
        generation++;

        gate.notifyAll();

        log.info(
                "PROFILING_RESULT_STATE state={} reason={}",
                state,
                reason
        );
    }

    private void submit(Runnable task) {
        synchronized (gate) {
            if (!executor.isShutdown()) {
                executor.execute(task);
            }
        }
    }

    private void cancelCheck() {
        if (pendingCheck != null) {
            pendingCheck.cancel(false);
            pendingCheck = null;
        }
    }

    private static Reason infrastructureReason(
            RuntimeException exception
    ) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception;
                cause != null && visited.add(cause);
                cause = cause.getCause()) {
            if (cause instanceof AmqpAuthenticationException) {
                return Reason.CONFIGURATION;
            }
            if (cause instanceof ShutdownSignalException shutdown) {
                if (shutdown.getReason() instanceof AMQP.Connection.Close close) {
                    return close.getReplyCode() == 320 && !shutdown.isInitiatedByApplication()
                            ? Reason.BROKER : Reason.CONFIGURATION;
                }
                if (shutdown.getReason() instanceof AMQP.Channel.Close) {
                    return Reason.CONFIGURATION;
                }
            }
        }

        if (exception instanceof RestClientResponseException response) {
            return response.getStatusCode().is5xxServerError()
                    ? Reason.BROKER
                    : Reason.CONFIGURATION;
        }

        if (exception instanceof AmqpConnectException
                || exception instanceof AmqpIOException
                || exception instanceof ResourceAccessException) {
            return Reason.BROKER;
        }

        if (exception instanceof IllegalStateException) {
            return Reason.CONFIGURATION;
        }

        return switch (ProfilingResultFailure.classify(exception)) {
            case TRANSIENT_DB -> Reason.TRANSIENT_DB;
            case CONFIGURATION -> Reason.CONFIGURATION;
            case UNCLASSIFIED -> Reason.UNCLASSIFIED;
        };
    }

    private void observe(String action, String outcome) {
        try {
            meters.counter(
                    "profiling.result.recovery",
                    "action",
                    action,
                    "outcome",
                    outcome
            ).increment();
        } catch (RuntimeException exception) {
            log.warn("PROFILING_RESULT_METRIC_FAILED");
        }
    }

    @Override
    public void onApplicationEvent(
            ListenerContainerConsumerFailedEvent event
    ) {
        if (event.getSource() == container) {
            Throwable failure = event.getThrowable();

            halt(
                    failure instanceof RuntimeException runtime
                            ? infrastructureReason(runtime)
                            : Reason.UNCLASSIFIED
            );
        }
    }

    @Override
    public void stop() {
        closeGate();

        synchronized (containerLifecycle) {
            container.stop();
        }
    }

    @Override
    public void stop(Runnable callback) {
        closeGate();

        synchronized (containerLifecycle) {
            container.stop(callback);
        }
    }

    private void closeGate() {
        synchronized (gate) {
            active = false;
            change(State.CLOSED, Reason.SHUTDOWN);
            cancelCheck();
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (gate) {
            return active;
        }
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void destroy() {
        stop();
        container.destroy();
    }

    private enum State {
        STOPPED, STARTING, RUNNING, PAUSED, CLOSED
    }

    public enum Reason {
        NONE,
        TRANSIENT_DB,
        BROKER,
        SETTLEMENT,
        MANUAL,
        CONFIGURATION,
        UNCLASSIFIED,
        AUTO_RESUME_LIMIT,
        STOP_FAILURE,
        SHUTDOWN;

        boolean automatic() {
            return this == TRANSIENT_DB
                    || this == BROKER
                    || this == SETTLEMENT;
        }
    }
}
