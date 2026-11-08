package com.gift.gift.domain.order.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.gift.gift.global.outbox.OutboxProperties;
import com.gift.gift.global.outbox.OutboxRelay;

import static org.mockito.Mockito.verify;

class PaymentOutboxRelayTest {

    @Test
    @DisplayName("결제 완료와 취소 Outbox를 명세의 exchange와 routing key로 발행한다")
    void relaysBothPaymentEventTypes() {
        OutboxRelay outboxRelay = Mockito.mock(OutboxRelay.class);
        OutboxProperties properties = new OutboxProperties(5, 60, 5000, 10, 2, 300);
        PaymentOutboxRelay relay = new PaymentOutboxRelay(outboxRelay, properties);

        relay.relayPaymentEvents();

        verify(outboxRelay).relay(
                "payment.order.completed.v1",
                5,
                "payment.events",
                "payment.order.completed"
        );
        verify(outboxRelay).relay(
                "payment.order.canceled.v1",
                5,
                "payment.events",
                "payment.order.canceled"
        );
    }
}
