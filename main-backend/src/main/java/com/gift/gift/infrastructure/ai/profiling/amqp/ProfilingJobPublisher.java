package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;

import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore.Claim;

@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "app.ai-profile.amqp",
        name = "enabled",
        havingValue = "true"
)
public class ProfilingJobPublisher {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    // 발행 확인 대기뿐 아니라 연결·채널 확보·전송을 포함한 제한 시간.
    private static final int ATTEMPT_TIMEOUT_SECONDS = 45;

    private final RabbitTemplate template;
    private final ProfilingAmqpProperties properties;
    private final ProfilingPublishStore store;
    private final ProfilingPublisherState state;
    private final MeterRegistry registry;

    private final ReentrantLock invocationLock = new ReentrantLock();
    private final AtomicBoolean workerBusy = new AtomicBoolean();

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            task -> {
                Thread thread = new Thread(
                        task,
                        "profiling-publisher"
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    public ProfilingJobPublisher(
            @Qualifier("profilingRabbitTemplate")
            RabbitTemplate template,
            ProfilingAmqpProperties properties,
            ProfilingPublishStore store,
            ProfilingPublisherState state,
            MeterRegistry registry
    ) {
        if (properties.confirmTimeoutSeconds()
                >= ATTEMPT_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "Confirm 대기는 전체 발행 시도 제한보다 짧아야 합니다."
            );
        }

        this.template = template;
        this.properties = properties;
        this.store = store;
        this.state = state;
        this.registry = registry;
        for (String code : Set.of("NOT_SENT", "ACK_NO_RETURN", "UNROUTABLE_MESSAGE", "PUBLISH_NACK",
                "CONFIRM_TIMEOUT", "ATTEMPT_TIMEOUT", "PUBLISH_INTERRUPTED", "INPUT_TOO_LARGE",
                "BROKER_AUTHENTICATION_FAILED", "BROKER_CONFIGURATION_ERROR", "BROKER_COMMUNICATION_FAILED")) {
            registry.counter("profiling.publish.outcomes", "code", code);
        }
        registry.counter("profiling.publish.stale.updates");
        registry.counter("profiling.publish.db.record.errors");
    }

    public enum TickResult {
        NO_WORK,
        PROGRESSED,
        STOP
    }

    public TickResult publishOne() {
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "발행은 DB 트랜잭션 밖에서 호출해야 합니다."
            );
        }

        if (!invocationLock.tryLock()) {
            return TickResult.STOP;
        }

        boolean submitted = false;
        boolean busyAcquired = false;

        try {
            if (state.unavailable()
                    || !workerBusy.compareAndSet(false, true)) {
                return TickResult.STOP;
            }

            busyAcquired = true;

            long epoch = state.epoch();

            Claim claim = store.claimOne().orElse(null);

            if (claim == null) {
                return TickResult.NO_WORK;
            }

            if (state.unavailable() || epoch != state.epoch()) {
                store.releaseUnsent(claim);
                return TickResult.STOP;
            }

            AtomicBoolean executionStarted = new AtomicBoolean();
            FutureTask<Outcome> task = new FutureTask<>(() -> {
                executionStarted.set(true);
                try {
                    if (state.unavailable()
                            || epoch != state.epoch()) {
                        return Outcome.unsent();
                    }

                    return send(claim);
                } finally {
                    workerBusy.set(false);
                }
            }) {
                @Override
                public void run() {
                    try {
                        super.run();
                    } finally {
                        // 실행 전 취소되면 Callable의 finally가 실행되지 않는다.
                        if (!executionStarted.get()) {
                            workerBusy.set(false);
                        }
                    }
                }
            };

            try {
                worker.execute(task);
                submitted = true;
            } catch (RejectedExecutionException exception) {
                store.releaseUnsent(claim);
                return TickResult.STOP;
            }

            Outcome outcome;

            try {
                outcome = task.get(
                        ATTEMPT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
            } catch (TimeoutException exception) {
                task.cancel(true);
                outcome = Outcome.retry("ATTEMPT_TIMEOUT");
            } catch (InterruptedException exception) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                outcome = Outcome.retry("PUBLISH_INTERRUPTED");
            } catch (ExecutionException exception) {
                outcome = classify(exception);
            }

            String outcomeCode = outcome.notSent() ? "NOT_SENT"
                    : outcome.success() ? "ACK_NO_RETURN" : outcome.code();
            registry.counter("profiling.publish.outcomes", "code", outcomeCode).increment();

            if (outcome.notSent()) {
                store.releaseUnsent(claim);
                return TickResult.STOP;
            }
            if (!outcome.success()) {
                log.warn("프로파일링 발행 실패: requestId={}, code={}", claim.requestId(), outcome.code());
            }
            // 브로커의 ACK와 DB 기록 성공은 별개다.
            try {
                boolean recorded = outcome.success() ? store.markPublished(claim)
                        : store.markFailed(claim, outcome.permanent(), outcome.code());
                if (!recorded) {
                    registry.counter("profiling.publish.stale.updates").increment();
                    log.warn("프로파일링 발행 결과 DB 반영 제외: requestId={}", claim.requestId());
                }
            } catch (RuntimeException exception) {
                registry.counter("profiling.publish.db.record.errors").increment();
                log.error("프로파일링 발행 결과 DB 기록 실패: requestId={}, errorType={}",
                        claim.requestId(), exception.getClass().getSimpleName());
                throw exception;
            }

            return state.unavailable() || workerBusy.get()
                    ? TickResult.STOP
                    : TickResult.PROGRESSED;
        } finally {
            if (busyAcquired && !submitted) {
                workerBusy.set(false);
            }

            invocationLock.unlock();
        }
    }

    private Outcome send(Claim claim) {
        byte[] body = claim.body()
                .getBytes(StandardCharsets.UTF_8);

        DistributionSummary.builder("profiling.publish.body.bytes")
                .serviceLevelObjectives(1024, 4096, 16384, 65536, 131072, 262144)
                .register(registry).record(body.length);

        if (body.length > MAX_BODY_BYTES) {
            return Outcome.blocked("INPUT_TOO_LARGE");
        }

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(claim.requestId());

        CorrelationData correlation =
                new CorrelationData(claim.token());

        try {
            template.send(
                    ProfilingAmqpConfiguration.EXCHANGE,
                    ProfilingAmqpConfiguration.REQUEST_ROUTING_KEY,
                    new Message(body, messageProperties),
                    correlation
            );

            CorrelationData.Confirm confirm;
            Timer.Sample confirmWait = Timer.start(registry);
            try {
                confirm = correlation.getFuture().get(
                        properties.confirmTimeoutSeconds(), TimeUnit.SECONDS);
            } finally {
                confirmWait.stop(Timer.builder("profiling.publish.confirm.wait")
                        .publishPercentileHistogram()
                        .minimumExpectedValue(java.time.Duration.ofMillis(1))
                        .maximumExpectedValue(java.time.Duration.ofSeconds(30))
                        .register(registry));
            }

            if (correlation.getReturned() != null) {
                return Outcome.blocked("UNROUTABLE_MESSAGE");
            }

            return confirm.ack()
                    ? Outcome.published()
                    : Outcome.retry("PUBLISH_NACK");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.retry("PUBLISH_INTERRUPTED");
        } catch (TimeoutException exception) {
            return Outcome.retry("CONFIRM_TIMEOUT");
        } catch (Exception exception) {
            return classify(exception);
        }
    }

    private Outcome classify(Throwable error) {
        for (Throwable cause = error;
             cause != null;
             cause = cause.getCause()) {

            if (cause instanceof AmqpAuthenticationException) {
                return Outcome.blocked("BROKER_AUTHENTICATION_FAILED");
            }

            if (cause instanceof ShutdownSignalException signal) {
                Object reason = signal.getReason();

                if (reason instanceof AMQP.Channel.Close close
                        && Set.of(403, 404, 405, 406)
                        .contains(close.getReplyCode())) {
                    return Outcome.blocked(
                            "BROKER_CONFIGURATION_ERROR"
                    );
                }

                if (reason instanceof AMQP.Connection.Close close
                        && close.getReplyCode() == 403) {
                    return Outcome.blocked(
                            "BROKER_AUTHENTICATION_FAILED"
                    );
                }
            }
        }

        return Outcome.retry("BROKER_COMMUNICATION_FAILED");
    }

    @PreDestroy
    public void stop() {
        state.beginShutdown();
        worker.shutdownNow();

        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record Outcome(
            boolean success,
            boolean permanent,
            boolean notSent,
            String code
    ) {
        static Outcome published() {
            return new Outcome(true, false, false, null);
        }

        static Outcome retry(String code) {
            return new Outcome(false, false, false, code);
        }

        static Outcome blocked(String code) {
            return new Outcome(false, true, false, code);
        }

        static Outcome unsent() {
            return new Outcome(false, false, true, null);
        }
    }
}
