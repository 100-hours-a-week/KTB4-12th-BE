package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class ProfilingResultBacklog {
    private static final Logger log = LoggerFactory.getLogger(ProfilingResultBacklog.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    private volatile Snapshot snapshot;
    private volatile double sampleOk;

    public ProfilingResultBacklog(
            DataSource dataSource,
            MeterRegistry meters,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(3);
        this.clock = clock;

        meters.gauge(
                "profiling.result.waiting.jobs",
                this,
                monitor -> monitor.snapshot == null
                        ? Double.NaN
                        : monitor.snapshot.count()
        );

        meters.gauge(
                "profiling.result.waiting.oldest.age.seconds",
                this,
                ProfilingResultBacklog::oldestAge
        );

        meters.gauge(
                "profiling.result.waiting.sample.ok",
                this,
                monitor -> monitor.sampleOk
        );

        meters.gauge(
                "profiling.result.waiting.sample.age.seconds",
                this,
                monitor -> {
                    Snapshot current = monitor.snapshot;

                    return current == null
                            ? Double.NaN
                            : Math.max(
                            0,
                            Duration.between(
                                    current.sampledAt(),
                                    monitor.clock.instant()
                            ).toSeconds()
                    );
                }
        );
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void refresh() {
        try {
            snapshot = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) AS waiting_count,
                           MIN(created_at) AS oldest_created_at
                    FROM profiling_jobs
                    WHERE result_status = 'WAITING'
                      AND publish_status = 'PUBLISHED'
                    """,
                    (row, index) -> new Snapshot(
                            row.getLong("waiting_count"),
                            row.getObject(
                                    "oldest_created_at",
                                    LocalDateTime.class
                            ),
                            clock.instant()
                    )
            );

            sampleOk = 1;
        } catch (RuntimeException exception) {
            sampleOk = 0;

            log.warn(
                    "PROFILING_RESULT_BACKLOG_SAMPLE_FAILED type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private double oldestAge() {
        Snapshot current = snapshot;

        if (current == null) {
            return Double.NaN;
        }

        return current.oldest() == null
                ? 0
                : Math.max(
                0,
                Duration.between(
                        current.oldest(),
                        LocalDateTime.ofInstant(
                                clock.instant(),
                                ZoneId.of("Asia/Seoul")
                        )
                ).toSeconds()
        );
    }

    private record Snapshot(
            long count,
            LocalDateTime oldest,
            Instant sampledAt
    ) {
    }
}
