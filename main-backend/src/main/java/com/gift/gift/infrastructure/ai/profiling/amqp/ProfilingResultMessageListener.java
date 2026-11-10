package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultMessageReader;

import static com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingResultListener.Reason.*;

public class ProfilingResultMessageListener implements ChannelAwareMessageListener {
    private static final Logger log =
            LoggerFactory.getLogger(ProfilingResultMessageListener.class);

    private final ProfilingResultMessageReader reader;
    private final ProfilingResultService service;
    private final ProfilingResultListener lifecycle;
    private final MeterRegistry meters;
    private final int unclassifiedMaxAttempts;
    private final RetryWait retryWait;

    public ProfilingResultMessageListener(
            ProfilingResultMessageReader reader,
            ProfilingResultService service,
            ProfilingResultListener lifecycle,
            MeterRegistry meters,
            int unclassifiedMaxAttempts,
            RetryWait retryWait
    ) {
        if (unclassifiedMaxAttempts < 1 || unclassifiedMaxAttempts > 10) {
            throw new IllegalArgumentException(
                    "미분류 시도 상한은 최초 포함 1~10회여야 합니다."
            );
        }

        this.reader = reader;
        this.service = service;
        this.lifecycle = lifecycle;
        this.meters = meters;
        this.unclassifiedMaxAttempts = unclassifiedMaxAttempts;
        this.retryWait = retryWait;
    }

    @Override
    public void onMessage(Message message, Channel channel) {
        if (lifecycle.isHalted()) {
            return;
        }

        if (channel == null) {
            lifecycle.halt(UNCLASSIFIED);
            return;
        }

        long started = System.nanoTime();

        try {
            ProfilingResultMessage resultMessage;

            try {
                resultMessage = reader.read(message.getBody());
                var properties = message.getMessageProperties();

                if (!Objects.equals(
                        resultMessage.requestId(),
                        properties.getMessageId()
                ) || !Objects.equals(
                        resultMessage.requestId(),
                        properties.getCorrelationId()
                )) {
                    throw new IllegalArgumentException("AMQP 식별자가 다릅니다.");
                }
            } catch (IllegalArgumentException exception) {
                observe("invalid");
                settle(
                        channel,
                        message.getMessageProperties().getDeliveryTag(),
                        false
                );
                return;
            }

            var saved = saveWithRetry(resultMessage);

            if (saved == null) {
                return;
            }

            observe(saved.decision().name());

            boolean accepted =
                    saved.decision() != ProfilingResultService.Decision.QUARANTINE;

            if (settle(
                    channel,
                    message.getMessageProperties().getDeliveryTag(),
                    accepted
            )) {
                lifecycle.settledSuccessfully();
            }
        } catch (RuntimeException exception) {
            log.error(
                    "PROFILING_RESULT_UNCLASSIFIED type={}",
                    exception.getClass().getSimpleName()
            );
            lifecycle.halt(UNCLASSIFIED);
        } finally {
            try {
                meters.timer("profiling.result.processing")
                        .record(
                                System.nanoTime() - started,
                                TimeUnit.NANOSECONDS
                        );
            } catch (RuntimeException exception) {
                log.warn("PROFILING_RESULT_METRIC_FAILED");
            }
        }
    }

    private ProfilingResultService.Result saveWithRetry(
            ProfilingResultMessage message
    ) {
        for (int attempt = 1; !lifecycle.isHalted(); attempt++) {
            var committed =
                    new AtomicReference<ProfilingResultService.Result>();

            try {
                // 프록시 호출이 끝나면 해당 REQUIRES_NEW 트랜잭션도 끝나 있다.
                return service.saveWithCommitObserver(
                        message,
                        committed::set
                );
            } catch (RuntimeException exception) {
                if (committed.get() != null) {
                    log.warn(
                            "PROFILING_RESULT_POST_COMMIT_FAILED "
                                    + "requestId={} type={}",
                            message.requestId(),
                            exception.getClass().getSimpleName()
                    );

                    observe("post_commit_failure");
                    return committed.get();
                }

                ProfilingResultFailure failure =
                        ProfilingResultFailure.classify(exception);

                int limit =
                        failure == ProfilingResultFailure.TRANSIENT_DB
                                ? 3
                                : unclassifiedMaxAttempts;

                log.warn(
                        "PROFILING_RESULT_SAVE_FAILED "
                                + "requestId={} kind={} attempt={} type={}",
                        message.requestId(),
                        failure,
                        attempt,
                        exception.getClass().getSimpleName()
                );

                if (failure == ProfilingResultFailure.CONFIGURATION
                        || attempt >= limit) {
                    lifecycle.halt(switch (failure) {
                        case TRANSIENT_DB -> TRANSIENT_DB;
                        case CONFIGURATION -> CONFIGURATION;
                        case UNCLASSIFIED -> UNCLASSIFIED;
                    });
                    return null;
                }

                observe("retry_" + failure.name());

                try {
                    if (!retryWait.await(Math.min(attempt, 2) * 1000L)) {
                        return null;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    lifecycle.halt(UNCLASSIFIED);
                    return null;
                }
            }
        }

        return null;
    }

    private boolean settle(
            Channel channel,
            long deliveryTag,
            boolean accepted
    ) {
        try {
            if (accepted) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicReject(deliveryTag, false);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "PROFILING_RESULT_SETTLEMENT_FAILED type={}",
                    exception.getClass().getSimpleName()
            );

            lifecycle.halt(SETTLEMENT);
            return false;
        }

        observe(accepted ? "ack" : "reject");
        return true;
    }

    private void observe(String outcome) {
        try {
            meters.counter(
                    "profiling.result.delivery",
                    "outcome",
                    outcome
            ).increment();
        } catch (RuntimeException exception) {
            log.warn("PROFILING_RESULT_METRIC_FAILED");
        }
    }

    @FunctionalInterface
    public interface RetryWait {
        boolean await(long millis) throws InterruptedException;
    }
}
