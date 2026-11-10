package com.gift.gift.domain.roulette.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.roulette.entity.RouletteAccount;
import com.gift.gift.domain.roulette.entity.RouletteParticipationDay;
import com.gift.gift.domain.roulette.repository.RouletteAccountRepository;
import com.gift.gift.domain.roulette.repository.RouletteParticipationDayRepository;
import com.gift.gift.domain.roulette.repository.RouletteTicketGrantRepository;
import com.gift.gift.domain.roulette.support.RoulettePolicy;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouletteServiceGiftGrantTest {

    private static final long SENDER_ID = 303L;
    private static final long GIFT_ID = 202L;
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 26);
    private static final Instant GRANTED_AT = Instant.parse("2026-10-26T15:00:01Z");
    private static final LocalDateTime GRANTED_AT_LOCAL =
            LocalDateTime.ofInstant(GRANTED_AT, RoulettePolicy.ZONE);

    @Mock
    private RouletteAccountRepository accountRepository;

    @Mock
    private RouletteParticipationDayRepository dayRepository;

    @Mock
    private RouletteTicketGrantRepository grantRepository;

    @Mock
    private CreditTransactionRepository creditTransactionRepository;

    @Mock
    private RouletteAccount account;

    @Mock
    private RouletteParticipationDay day;

    private RouletteService rouletteService;

    @BeforeEach
    void setUp() {
        rouletteService = new RouletteService(
                accountRepository,
                dayRepository,
                grantRepository,
                creditTransactionRepository,
                Clock.fixed(GRANTED_AT, RoulettePolicy.ZONE)
        );

        when(accountRepository.findByUserIdForUpdate(SENDER_ID))
                .thenReturn(Optional.of(account));
        when(dayRepository.findByUserIdAndDateForUpdate(SENDER_ID, PAYMENT_DATE))
                .thenReturn(Optional.of(day));
    }

    @Test
    @DisplayName("결제일에 지급 이력을 기록하고 새 이력일 때만 참여권과 일별 건수를 증가시킨다")
    void grantGift_newGrant_usesPaymentDateAndIncrementsCounters() {
        when(grantRepository.insertIfAbsent(
                SENDER_ID,
                PAYMENT_DATE,
                "GIFT:" + GIFT_ID,
                "GIFT",
                GRANTED_AT_LOCAL
        )).thenReturn(1);

        rouletteService.grantGift(SENDER_ID, GIFT_ID, PAYMENT_DATE);

        verify(dayRepository).insertIfAbsent(SENDER_ID, PAYMENT_DATE);
        verify(grantRepository).insertIfAbsent(
                SENDER_ID,
                PAYMENT_DATE,
                "GIFT:" + GIFT_ID,
                "GIFT",
                GRANTED_AT_LOCAL
        );
        verify(day).grantGift();
        verify(account).grantTicket();
    }

    @Test
    @DisplayName("같은 선물의 기존 지급 이력이 있으면 참여권과 일별 건수를 다시 늘리지 않는다")
    void grantGift_duplicateGrant_doesNotIncrementCounters() {
        when(grantRepository.insertIfAbsent(
                SENDER_ID,
                PAYMENT_DATE,
                "GIFT:" + GIFT_ID,
                "GIFT",
                GRANTED_AT_LOCAL
        )).thenReturn(0);

        rouletteService.grantGift(SENDER_ID, GIFT_ID, PAYMENT_DATE);

        verify(day, never()).grantGift();
        verify(account, never()).grantTicket();
    }
}
