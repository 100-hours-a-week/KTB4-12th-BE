package com.gift.gift.domain.roulette.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.roulette.support.RoulettePolicy;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouletteGrantTransactionServiceTest {

    private static final long ORDER_RECORD_ID = 101L;
    private static final long GIFT_ID = 202L;
    private static final long PAYER_ID = 303L;
    private static final LocalDateTime PAYMENT_COMPLETED_AT =
            LocalDateTime.of(2026, 10, 26, 23, 59, 59);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private GiftHistoryRepository giftHistoryRepository;

    @Mock
    private RouletteService rouletteService;

    @Mock
    private Order order;

    @Mock
    private GiftHistory gift;

    @Mock
    private User sender;

    private RouletteGrantTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new RouletteGrantTransactionService(
                orderRepository,
                giftHistoryRepository,
                rouletteService
        );
    }

    @Test
    @DisplayName("주문 결제자와 선물 발신자가 다르면 참여권 지급을 거부한다")
    void grantForCompletedPayment_senderMismatch_doesNotGrant() {
        when(orderRepository.findById(ORDER_RECORD_ID)).thenReturn(Optional.of(order));
        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID)).thenReturn(Optional.of(gift));
        when(order.getPayerId()).thenReturn(PAYER_ID);
        when(gift.getSender()).thenReturn(sender);
        when(sender.getId()).thenReturn(PAYER_ID + 1);

        assertThatThrownBy(() -> transactionService.grantForCompletedPayment(
                ORDER_RECORD_ID,
                PAYMENT_COMPLETED_AT
        )).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(rouletteService);
    }
}
