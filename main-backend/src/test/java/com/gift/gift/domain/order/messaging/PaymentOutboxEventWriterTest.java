package com.gift.gift.domain.order.messaging;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.order.messaging.PaymentOutboxEventWriter.PaymentCanceledOutboxRequested;
import com.gift.gift.domain.order.messaging.PaymentOutboxEventWriter.PaymentCompletedOutboxRequested;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryRepository;
import com.gift.gift.global.outbox.OutboxEventWriter;
import com.gift.gift.global.outbox.OutboxEventWriter.Event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxEventWriterTest {

    private static final LocalDateTime PAYMENT_COMPLETED_AT =
            LocalDateTime.of(2026, 11, 7, 12, 30, 45);
    private static final LocalDateTime LOCAL_CANCELLED_AT =
            LocalDateTime.of(2026, 11, 7, 14, 10, 0);

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private PaymentReceiptDeliveryRepository receiptRepository;

    private PaymentOutboxEventWriter writer;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                LOCAL_CANCELLED_AT.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")
        );

        writer = new PaymentOutboxEventWriter(
                outboxEventWriter,
                receiptRepository,
                new ObjectMapper(),
                clock
        );
    }

    @Test
    @DisplayName("결제 완료는 숫자 주문 ID payload와 거래 당시 발송 자격을 기록한다")
    void writesPaymentCompletedEnvelopeAndReceiptStatus() {
        writer.writePaymentCompleted(
                new PaymentCompletedOutboxRequested(
                        101L,
                        PAYMENT_COMPLETED_AT,
                        true
                )
        );

        verify(receiptRepository).createPayment(
                101L,
                PAYMENT_COMPLETED_AT,
                true
        );

        ArgumentCaptor<Event> captor =
                ArgumentCaptor.forClass(Event.class);
        verify(outboxEventWriter).enqueue(captor.capture());

        Event event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("payment.order.completed.v1");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.deduplicationKey()).isEqualTo("101");
        assertThat(event.aggregateType()).isEqualTo("ORDER");
        assertThat(event.aggregateId()).isEqualTo("101");
        assertThat(event.occurredAt()).isEqualTo(PAYMENT_COMPLETED_AT);
        assertThat(event.payload().get("orderRecordId").asLong()).isEqualTo(101L);
        assertThat(event.payload().size()).isEqualTo(1);
        assertThat(event.payload().has("orderRecordId")).isTrue();
    }

    @Test
    @DisplayName("취소 완료는 취소 ID를 payload에 담고 로컬 처리 시각을 사용한다")
    void writesPaymentCanceledEnvelopeAndReceiptStatus() {
        writer.writePaymentCanceled(
                new PaymentCanceledOutboxRequested(
                        202L,
                        PAYMENT_COMPLETED_AT,
                        false
                )
        );

        verify(receiptRepository).createCancellation(
                202L,
                PAYMENT_COMPLETED_AT,
                false
        );

        ArgumentCaptor<Event> captor =
                ArgumentCaptor.forClass(Event.class);
        verify(outboxEventWriter).enqueue(captor.capture());

        Event event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("payment.order.canceled.v1");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.deduplicationKey()).isEqualTo("202");
        assertThat(event.aggregateType()).isEqualTo("PAYMENT_CANCELLATION");
        assertThat(event.aggregateId()).isEqualTo("202");
        assertThat(event.occurredAt()).isEqualTo(LOCAL_CANCELLED_AT);
        assertThat(event.payload().get("cancellationId").asLong()).isEqualTo(202L);
        assertThat(event.payload().size()).isEqualTo(1);
        assertThat(event.payload().has("cancellationId")).isTrue();
    }
}
