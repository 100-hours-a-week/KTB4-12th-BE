package com.gift.gift.domain.bugreport.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import com.gift.gift.global.outbox.OutboxRelay;

@Slf4j
@RequiredArgsConstructor
public class ReportOutboxRelay {
    private final OutboxRelay relay;
    private final ReportTopologyPreflight preflight;
    private final ReportMqProperties settings;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}", scheduler = "outboxTaskScheduler")
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
