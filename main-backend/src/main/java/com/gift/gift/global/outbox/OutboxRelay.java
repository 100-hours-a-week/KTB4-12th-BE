package com.gift.gift.global.outbox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
public class OutboxRelay {
    private final OutboxClaimRepository repository;
    private final RabbitPublisher publisher;
    private final OutboxProperties settings;
    private final RabbitRoutingProperties routing;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}", scheduler = "outboxTaskScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void relaySearch() {
        relay("product.search.sync.v1", settings.searchBatchSize(), routing.searchRoutingKey());
    }

    private void relay(String eventType, int limit, String routingKey) {
        List<Job> jobs = repository.claim(eventType, limit);
        List<CompletableFuture<RabbitPublisher.Result>> attempts = jobs.stream()
                .map(job -> publisher.publish(job.envelope(), job.claimToken(), routingKey)).toList();
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
