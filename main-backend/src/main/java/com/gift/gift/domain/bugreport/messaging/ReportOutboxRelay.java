package com.gift.gift.domain.bugreport.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.gift.gift.global.outbox.OutboxRelay;

@Slf4j
@RequiredArgsConstructor
public class ReportOutboxRelay implements InitializingBean, DisposableBean {
    private final OutboxRelay relay;
    private final ReportTopologyPreflight preflight;
    private final ReportMqProperties settings;

    private final ThreadPoolTaskScheduler reportScheduler = new ThreadPoolTaskScheduler();
    @Value("${app.outbox.poll-interval-ms:1000}")
    private long pollIntervalMillis = 1000;

    @Override
    public void afterPropertiesSet() {
        if (pollIntervalMillis <= 0) throw new IllegalArgumentException("신고 발행 간격은 양수여야 합니다.");
        reportScheduler.setPoolSize(1);
        reportScheduler.setThreadNamePrefix("report-outbox-");
        reportScheduler.setWaitForTasksToCompleteOnShutdown(true);
        reportScheduler.setAwaitTerminationSeconds(30);
        reportScheduler.initialize();
        reportScheduler.scheduleWithFixedDelay(this::relayReports,
                Instant.now().plusMillis(pollIntervalMillis), Duration.ofMillis(pollIntervalMillis));
    }

    @Override
    public void destroy() {
        reportScheduler.shutdown();
    }

    public void relayReports() {
        try {
            preflight.verify();
        } catch (RuntimeException exception) {
            log.warn("리포트 MQ 발행 보류: topology preflight 실패, errorType={}",
                    exception.getClass().getSimpleName());
            return;
        }
        relay.relay(ReportMqProperties.EVENT_TYPE, settings.batchSize(),
                ReportMqProperties.EVENTS, ReportMqProperties.CREATED);
    }
}
