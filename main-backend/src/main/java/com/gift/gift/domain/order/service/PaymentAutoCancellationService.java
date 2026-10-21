package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

@Slf4j
@Service
public class PaymentAutoCancellationService {

    private static final String IDEMPOTENCY_KEY_PREFIX =
            "LOCAL_PROCESSING_FAILURE:";

    private final PaymentCancellationService cancellationService;
    private final PaymentCancellationRepository cancellationRepository;
    private final PaymentAutoCancellationTransactionService transactionService;
    private final Clock clock;
    private final Duration timeout;

    public PaymentAutoCancellationService(
            PaymentCancellationService cancellationService,
            PaymentCancellationRepository cancellationRepository,
            PaymentAutoCancellationTransactionService transactionService,
            Clock clock,
            @Value("${app.payment.reconciliation.timeout:PT10M}") Duration timeout
    ) {
        this.cancellationService = cancellationService;
        this.cancellationRepository = cancellationRepository;
        this.transactionService = transactionService;
        this.clock = clock;
        this.timeout = timeout;
    }

    public void cancel(Order order) {
        String idempotencyKey = idempotencyKey(order.getOrderId());

        try {
            PaymentCancellationApproval approval = cancellationService.request(
                    order.getPayerId(),
                    order.getOrderId(),
                    idempotencyKey,
                    CancellationSource.LOCAL_PROCESSING_FAILURE
            );
            transactionService.complete(approval);
        } catch (RuntimeException exception) {
            requireManualReviewIfExpired(order, idempotencyKey);
            throw exception;
        }
    }

    private void requireManualReviewIfExpired(
            Order order,
            String idempotencyKey
    ) {
        cancellationRepository
                .findByRequesterIdAndIdempotencyKey(
                        order.getPayerId(),
                        idempotencyKey
                )
                .filter(this::isExpired)
                .ifPresent(cancellation -> {
                    transactionService.markReconciliationRequired(
                            order.getId(),
                            cancellation.getId()
                    );
                    log.error(
                            "자동 보상 취소 결과를 확정하지 못해 운영 확인 대상으로 전환했습니다. orderId={}",
                            order.getOrderId()
                    );
                });
    }

    private boolean isExpired(PaymentCancellation cancellation) {
        return cancellation.getCreatedAt() != null
                && !cancellation.getCreatedAt()
                .plus(timeout)
                .isAfter(LocalDateTime.now(clock));
    }

    private String idempotencyKey(String orderId) {
        return IDEMPOTENCY_KEY_PREFIX + orderId;
    }
}
