package com.gift.gift.global.outbox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.global.messaging.RabbitRoutingProperties;
import com.gift.gift.global.outbox.OutboxClaimRepository.Job;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
public class OutboxRelay {
    private final OutboxClaimRepository repository;
    private final RabbitPublisher publisher;
    private final OutboxProperties settings;
    private final RabbitRoutingProperties routing;

    @Autowired
    public OutboxRelay(OutboxClaimRepository repository, RabbitPublisher publisher, OutboxProperties settings,
                       ObjectProvider<RabbitRoutingProperties> routing) {
        this(repository, publisher, settings, routing.getIfAvailable());
    }

    public OutboxRelay(OutboxClaimRepository repository, RabbitPublisher publisher, OutboxProperties settings,
                       RabbitRoutingProperties routing) {
        this.repository = repository;
        this.publisher = publisher;
        this.settings = settings;
        this.routing = routing;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}", scheduler = "outboxTaskScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void relaySearch() {
        if (routing == null) return; // 신고 전용 프로세스에서는 검색 이벤트를 선점하지 않는다.
        relay("product.search.sync.v1", settings.searchBatchSize(), routing.exchange(), routing.searchRoutingKey());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void relay(String eventType, int limit, String exchange, String routingKey) {
        List<Job> jobs = repository.claim(eventType, limit);
        List<CompletableFuture<RabbitPublisher.Result>> attempts = jobs.stream()
                .map(job -> publisher.publish(job.envelope(), job.claimToken(), exchange, routingKey)).toList();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            try {
                RabbitPublisher.Result result = attempts.get(i).join();
                if (result.confirmed()) {
                    repository.complete(job);
                } else {
                    repository.fail(job, result.errorType());
                    log.warn("Outbox 발행 재시도: eventId={}, errorType={}", job.envelope().eventId(), result.errorType());
                }
            } catch (RuntimeException exception) {
                // DB 갱신 실패는 임대 만료 후 같은 eventId로 복구한다.
                log.error("Outbox 결과 저장 실패: eventId={}, errorType={}",
                        job.envelope().eventId(), exception.getClass().getSimpleName());
            }
        }
    }
}
