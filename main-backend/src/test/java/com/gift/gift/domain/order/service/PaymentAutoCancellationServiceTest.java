package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentAutoCancellationServiceTest {

    private static final Long ORDER_RECORD_ID = 1L;
    private static final Long PAYER_ID = 2L;
    private static final String ORDER_ID =
            "550e8400-e29b-41d4-a716-446655440000";
    private static final String IDEMPOTENCY_KEY =
            "LOCAL_PROCESSING_FAILURE:" + ORDER_ID;

    private PaymentCancellationService cancellationService;
    private PaymentCancellationRepository cancellationRepository;
    private PaymentAutoCancellationTransactionService transactionService;
    private PaymentAutoCancellationService service;

    @BeforeEach
    void setUp() {
        cancellationService = mock(PaymentCancellationService.class);
        cancellationRepository = mock(PaymentCancellationRepository.class);
        transactionService = mock(
                PaymentAutoCancellationTransactionService.class
        );
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-17T12:00:00Z"),
                ZoneOffset.UTC
        );
        service = new PaymentAutoCancellationService(
                cancellationService,
                cancellationRepository,
                transactionService,
                clock,
                Duration.ofMinutes(10)
        );
    }

    @Test
    @DisplayName("주문번호 기반의 같은 멱등 키로 자동 보상 취소를 완료한다")
    void cancel_usesDeterministicIdempotencyKey() {
        Order order = order();
        PaymentCancellationApproval approval = mock(
                PaymentCancellationApproval.class
        );
        when(cancellationService.request(
                PAYER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.LOCAL_PROCESSING_FAILURE
        )).thenReturn(approval);

        service.cancel(order);

        verify(transactionService).complete(approval);
    }

    private Order order() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(ORDER_RECORD_ID);
        when(order.getPayerId()).thenReturn(PAYER_ID);
        when(order.getOrderId()).thenReturn(ORDER_ID);
        return order;
    }
}
