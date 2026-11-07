package com.gift.gift.domain.recommendation.profiling.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore.PublishBacklog;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingPublisherState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProfilingPublishMonitorTest {
    private final ProfilingPublishStore store = mock(ProfilingPublishStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private final ProfilingPublishMonitor monitor = new ProfilingPublishMonitor(
            store, registry, clock, new ProfilingPublisherState());

    @Test
    void successfulEmptySnapshot_reportsZeroInsteadOfUnknown() {
        when(store.findPublishBacklog()).thenReturn(List.of());
        assertThat(count()).isNaN();
        assertThat(value("profiling.publish.db.sample.age.seconds")).isInfinite();
        monitor.refresh();
        assertThat(count()).isZero();
        assertThat(value("profiling.publish.db.sample.ok")).isEqualTo(1);
        assertThat(value("profiling.publish.db.sample.age.seconds")).isZero();
    }

    @Test
    void failure_preservesSnapshotAndMarksCollectionUnhealthy() {
        when(store.findPublishBacklog())
                .thenReturn(List.of(new PublishBacklog("READY", 3,
                                LocalDateTime.of(2026, 11, 7, 8, 58)),
                        new PublishBacklog("BLOCKED", 1, LocalDateTime.of(2000, 1, 1, 0, 0))))
                .thenThrow(new IllegalStateException("sensitive text must not be logged"));
        monitor.refresh();
        assertThat(value("profiling.publish.oldest.age.seconds")).isEqualTo(120);
        monitor.refresh();
        assertThat(count()).isEqualTo(3);
        assertThat(value("profiling.publish.db.sample.ok")).isZero();
        assertThat(registry.get("profiling.publish.db.sample.errors").counter().count()).isEqualTo(1);
    }

    private double count() {
        return registry.get("profiling.publish.jobs").tag("publish_status", "READY").gauge().value();
    }

    private double value(String name) {
        return registry.get(name).gauge().value();
    }
}
