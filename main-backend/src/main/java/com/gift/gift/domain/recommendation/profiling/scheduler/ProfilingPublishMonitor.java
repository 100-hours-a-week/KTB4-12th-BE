package com.gift.gift.domain.recommendation.profiling.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore.PublishBacklog;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingPublisherState;

@Slf4j
@Component
@ConditionalOnProperty(name = {"app.ai-profile.scheduling-enabled", "app.ai-profile.amqp.enabled"},
        havingValue = "true")
public class ProfilingPublishMonitor {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ProfilingPublishStore store;
    private final MeterRegistry registry;
    private final Clock clock;
    private volatile Snapshot snapshot;
    private volatile double sampleOk;

    public ProfilingPublishMonitor(ProfilingPublishStore store, MeterRegistry registry,
                                   Clock clock, ProfilingPublisherState state) {
        this.store = store;
        this.registry = registry;
        this.clock = clock;
        for (String status : Set.of("READY", "RETRY_WAIT", "CLAIMED", "BLOCKED")) {
            Gauge.builder("profiling.publish.jobs", this, monitor -> monitor.count(status))
                    .tag("publish_status", status).register(registry);
        }
        Gauge.builder("profiling.publish.oldest.age.seconds", this,
                ProfilingPublishMonitor::oldestAgeSeconds).register(registry);
        Gauge.builder("profiling.publish.db.sample.ok", this, monitor -> monitor.sampleOk).register(registry);
        Gauge.builder("profiling.publish.db.sample.age.seconds", this,
                ProfilingPublishMonitor::sampleAgeSeconds).register(registry);
        Gauge.builder("profiling.publish.connection.blocked", state,
                connection -> connection.isBlocked() ? 1 : 0).register(registry);
    }

    @Scheduled(fixedDelay = 60_000)
    public void refresh() {
        try {
            Map<String, PublishBacklog> rows = store.findPublishBacklog().stream()
                    .collect(Collectors.toUnmodifiableMap(PublishBacklog::publishStatus, Function.identity()));
            snapshot = new Snapshot(rows, clock.instant());
            sampleOk = 1;
        } catch (RuntimeException exception) {
            // 조회 실패를 작업 0건으로 오인하지 않도록 이전 값을 유지한다.
            sampleOk = 0;
            registry.counter("profiling.publish.db.sample.errors").increment();
            log.warn("프로파일링 발행 상태 지표 조회 실패: errorType={}", exception.getClass().getSimpleName());
        }
    }

    private double count(String status) {
        Snapshot current = snapshot;
        if (current == null) {
            return Double.NaN;
        }
        PublishBacklog row = current.rows().get(status);
        return row == null ? 0 : row.count();
    }

    private double oldestAgeSeconds() {
        Snapshot current = snapshot;
        if (current == null) {
            return Double.NaN;
        }
        LocalDateTime oldest = current.rows().values().stream()
                .filter(row -> !"BLOCKED".equals(row.publishStatus()))
                .map(PublishBacklog::oldestCreatedAt).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest,
                LocalDateTime.ofInstant(clock.instant(), SEOUL)).toMillis() / 1000.0);
    }

    private double sampleAgeSeconds() {
        Snapshot current = snapshot;
        return current == null ? Double.POSITIVE_INFINITY
                : Math.max(0, Duration.between(current.observedAt(), clock.instant()).toMillis() / 1000.0);
    }

    private record Snapshot(Map<String, PublishBacklog> rows, Instant observedAt) {
    }
}
