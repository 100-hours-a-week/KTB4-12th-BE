package com.gift.gift.domain.gift.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.gift.gift.domain.gift.repository.GiftExpirationCandidate;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.service.PaymentCancellationService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GiftExpirationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final LocalDateTime EXPIRES_AT =
            LocalDateTime.of(2026, 10, 15, 0, 0);

    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private GiftHistoryRepository giftHistoryRepository;
    private OrderRepository orderRepository;
    private PaymentCancellationService cancellationService;
    private GiftExpirationService service;

    @BeforeEach
    void setUp() {
        giftHistoryRepository = mock(GiftHistoryRepository.class);

        orderRepository = mock(OrderRepository.class);

        cancellationService = mock(PaymentCancellationService.class);

        Clock clock = Clock.fixed(Instant.parse("2026-10-15T18:00:00Z"), ZONE);

        service = new GiftExpirationService(
                giftHistoryRepository,
                orderRepository,
                cancellationService,
                clock
        );
    }

    @Test
    @DisplayName("한 배치가 가득 차면 다음 배치를 계속 조회한다")
    void expireAll_repeatsUntilNoCandidatesRemain() {
        List<GiftExpirationCandidate> firstBatch =
                IntStream.rangeClosed(1, 1_000)
                        .mapToObj(this::candidate)
                        .toList();

        GiftExpirationCandidate remaining = candidate(1_001);

        Order order = order();

        when(giftHistoryRepository.findExpired(
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(firstBatch);

        when(giftHistoryRepository.findExpiredAfter(
                any(LocalDateTime.class),
                eq(EXPIRES_AT),
                eq(1_000L),
                any(Pageable.class)
        )).thenReturn(List.of(remaining));

        when(orderRepository.findById(anyLong()))
                .thenReturn(Optional.of(order));

        service.expireAll();

        verify(cancellationService, times(1_001))
                .cancel(
                        eq(1L),
                        eq(ORDER_ID),
                        anyString(),
                        eq(CancellationSource.GIFT_EXPIRATION)
                );

        verify(cancellationService).cancel(
                1L,
                ORDER_ID,
                "GIFT_EXPIRATION:1001",
                CancellationSource.GIFT_EXPIRATION
        );
    }

    @Test
    @DisplayName("배치 단위로 선물 만료 처리 시, 한 건의 선물 취소 실패는 다음 선물의 만료 처리를 막지 않는다.")
    void expireAll_continuesAfterIndividualFailure() {
        List<GiftExpirationCandidate> candidates =
                List.of(
                        candidate(1),
                        candidate(2),
                        candidate(3)
                );

        Order order = order();

        when(giftHistoryRepository.findExpired(
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(candidates);

        when(orderRepository.findById(anyLong()))
                .thenReturn(Optional.of(order));

        when(cancellationService.cancel(
                1L,
                ORDER_ID,
                "GIFT_EXPIRATION:2",
                CancellationSource.GIFT_EXPIRATION
        )).thenThrow(
                new IllegalStateException("취소 처리 실패")
        );

        service.expireAll();

        verify(cancellationService).cancel(
                1L,
                ORDER_ID,
                "GIFT_EXPIRATION:1",
                CancellationSource.GIFT_EXPIRATION
        );

        verify(cancellationService).cancel(
                1L,
                ORDER_ID,
                "GIFT_EXPIRATION:2",
                CancellationSource.GIFT_EXPIRATION
        );

        verify(cancellationService).cancel(
                1L,
                ORDER_ID,
                "GIFT_EXPIRATION:3",
                CancellationSource.GIFT_EXPIRATION
        );
    }

    private GiftExpirationCandidate candidate(
            int id
    ) {
        return new GiftExpirationCandidate(
                (long) id,
                1L,
                (long) id,
                EXPIRES_AT
        );
    }

    private Order order() {
        return new Order(
                ORDER_ID,
                1L,
                2L,
                3L,
                1,
                32_000L,
                32_000L,
                "payment-" + UUID.randomUUID(),
                "a".repeat(64)
        );
    }
}
