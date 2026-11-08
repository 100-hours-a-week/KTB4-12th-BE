package com.gift.gift.domain.order.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gift.gift.global.outbox.OutboxProperties;
import com.gift.gift.global.outbox.OutboxRelay;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.payment.mq.enabled",
        havingValue = "true"
)
public class PaymentOutboxRelay {

    private static final String EXCHANGE = "payment.events";

    private final OutboxRelay outboxRelay;
    private final OutboxProperties settings;

    @Scheduled(
            fixedDelayString = "${app.outbox.poll-interval-ms:1000}",
            scheduler = "outboxTaskScheduler"
    )
    public void relayPaymentEvents() {
        int batchSize = settings.searchBatchSize();

        outboxRelay.relay("payment.order.completed.v1", batchSize, EXCHANGE, "payment.order.completed");
        outboxRelay.relay("payment.order.canceled.v1", batchSize, EXCHANGE, "payment.order.canceled");
    }
}
