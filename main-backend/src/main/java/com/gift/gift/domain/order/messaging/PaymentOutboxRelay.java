package com.gift.gift.domain.order.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gift.gift.global.outbox.OutboxRelay;

@Service
@ConditionalOnProperty(
        name = "app.payment.mq.enabled",
        havingValue = "true"
)
public class PaymentOutboxRelay {

    private static final String EXCHANGE = "payment.events";

    private final OutboxRelay outboxRelay;
    private final int batchSize;

    // 공용 app.outbox 값(10건/1초)은 결제 피크에서 발행이 병목이라, 상품 검색 등 다른 발행기에 영향 없이 결제만 따로 받는다.
    public PaymentOutboxRelay(
            OutboxRelay outboxRelay,
            @Value("${app.payment.mq.outbox.batch-size:100}") int batchSize
    ) {
        this.outboxRelay = outboxRelay;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.payment.mq.outbox.poll-interval-ms:500}",
            scheduler = "outboxTaskScheduler"
    )
    public void relayPaymentEvents() {
        outboxRelay.relay("payment.order.completed.v1", batchSize, EXCHANGE, "payment.order.completed");
        outboxRelay.relay("payment.order.canceled.v1", batchSize, EXCHANGE, "payment.order.canceled");
    }
}
