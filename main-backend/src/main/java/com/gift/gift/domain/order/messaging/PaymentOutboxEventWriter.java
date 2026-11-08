package com.gift.gift.domain.order.messaging;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryRepository;
import com.gift.gift.global.outbox.OutboxEventWriter;
import com.gift.gift.global.outbox.OutboxEventWriter.Event;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.payment.mq.enabled",
        havingValue = "true"
)
public class PaymentOutboxEventWriter {

    private static final String PAYMENT_COMPLETED_TYPE = "payment.order.completed.v1";
    private static final String PAYMENT_CANCELED_TYPE = "payment.order.canceled.v1";

    private final OutboxEventWriter outboxEventWriter;
    private final PaymentReceiptDeliveryRepository receiptRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void writePaymentCompleted(PaymentCompletedOutboxRequested request) {
        long orderRecordId = requirePositive(request.orderRecordId());

        receiptRepository.createPayment(
                orderRecordId,
                request.completedAt(),
                request.receiptEligible()
        );

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderRecordId", orderRecordId);

        outboxEventWriter.enqueue(new Event(
                PAYMENT_COMPLETED_TYPE,
                1,
                Long.toString(orderRecordId),
                "ORDER",
                Long.toString(orderRecordId),
                null,
                payload,
                request.completedAt(),
                null
        ));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void writePaymentCanceled(PaymentCanceledOutboxRequested request) {
        long cancellationId = requirePositive(request.cancellationId());

        receiptRepository.createCancellation(
                cancellationId,
                request.paymentCompletedAt(),
                request.receiptEligible()
        );

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cancellationId", cancellationId);

        outboxEventWriter.enqueue(new Event(
                PAYMENT_CANCELED_TYPE,
                1,
                Long.toString(cancellationId),
                "PAYMENT_CANCELLATION",
                Long.toString(cancellationId),
                null,
                payload,
                LocalDateTime.now(clock),
                null
        ));
    }

    private long requirePositive(Long id) {
        Objects.requireNonNull(id, "이벤트 원본 ID");

        if (id <= 0) {
            throw new IllegalArgumentException("이벤트 원본 ID는 양수여야 합니다.");
        }

        return id;
    }

    public record PaymentCompletedOutboxRequested(
            Long orderRecordId,
            LocalDateTime completedAt,
            boolean receiptEligible
    ) {
        public PaymentCompletedOutboxRequested {
            Objects.requireNonNull(orderRecordId, "orderRecordId");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    public record PaymentCanceledOutboxRequested(
            Long cancellationId,
            LocalDateTime paymentCompletedAt,
            boolean receiptEligible
    ) {
        public PaymentCanceledOutboxRequested {
            Objects.requireNonNull(cancellationId, "cancellationId");
            Objects.requireNonNull(paymentCompletedAt, "paymentCompletedAt");
        }
    }
}
