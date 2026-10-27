package com.gift.gift.domain.roulette.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.roulette.dto.response.RouletteAttendanceResponse;
import com.gift.gift.domain.roulette.dto.response.RouletteStatusResponse;
import com.gift.gift.domain.roulette.entity.RouletteAccount;
import com.gift.gift.domain.roulette.entity.RouletteParticipationDay;
import com.gift.gift.domain.roulette.entity.RouletteTicketSource;
import com.gift.gift.domain.roulette.repository.RouletteAccountRepository;
import com.gift.gift.domain.roulette.repository.RouletteParticipationDayRepository;
import com.gift.gift.domain.roulette.repository.RouletteTicketGrantRepository;
import com.gift.gift.domain.roulette.support.RouletteDay;
import com.gift.gift.domain.roulette.support.RoulettePolicy;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class RouletteService {

    private final RouletteAccountRepository accountRepository;
    private final RouletteParticipationDayRepository dayRepository;
    private final RouletteTicketGrantRepository grantRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RouletteStatusResponse status(Long userId) {
        Instant now = clock.instant();
        LocalDate date = now.atZone(RoulettePolicy.ZONE).toLocalDate();
        long remaining = accountRepository.findById(userId)
                .map(RouletteAccount::getAvailableTickets).orElse(0L);
        RouletteDay day = dayRepository.findByUserIdAndParticipationDate(userId, date)
                .map(value -> toDay(value, remaining))
                .orElse(new RouletteDay(date, 0, 0, 0, remaining));
        return RouletteStatusResponse.from(day, now);
    }

    @Transactional
    public RouletteAttendanceResponse attend(Long userId) {
        RouletteAccount account = lockAccount(userId);
        Instant now = clock.instant();
        LocalDate date = now.atZone(RoulettePolicy.ZONE).toLocalDate();
        RouletteParticipationDay day = initializeDay(userId, date);
        String key = "ATTENDANCE:" + userId + ":" + date;
        boolean legacyReward = creditTransactionRepository.existsByDeduplicationKey(
                "DAILY_ATTENDANCE_REWARD:" + userId + ":" + date
        );
        boolean granted = false;
        if (!legacyReward && grantRepository.insertIfAbsent(userId, date, key, RouletteTicketSource.ATTENDANCE.name(),
                LocalDateTime.ofInstant(now, RoulettePolicy.ZONE)) == 1) {
            day.grantAttendance();
            account.grantTicket();
            granted = true;
        }
        return RouletteAttendanceResponse.from(granted, toDay(day, account.getAvailableTickets()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grantGift(Long senderId, Long giftId) {
        RouletteAccount account = lockAccount(senderId);
        Instant now = clock.instant();
        LocalDate date = now.atZone(RoulettePolicy.ZONE).toLocalDate();
        RouletteParticipationDay day = initializeDay(senderId, date);
        String key = "GIFT:" + giftId;
        if (grantRepository.insertIfAbsent(senderId, date, key, RouletteTicketSource.GIFT.name(),
                LocalDateTime.ofInstant(now, RoulettePolicy.ZONE)) == 1) {
            day.grantGift();
            account.grantTicket();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RouletteDay consumeTicket(Long userId) {
        RouletteAccount account = lockAccount(userId);
        LocalDate date = clock.instant().atZone(RoulettePolicy.ZONE).toLocalDate();
        RouletteParticipationDay day = initializeDay(userId, date);
        if (account.getAvailableTickets() == 0) {
            throw new BusinessException(ErrorCode.ROULETTE_TICKET_NOT_AVAILABLE);
        }
        if (day.getUsed() >= 1) {
            throw new BusinessException(ErrorCode.ROULETTE_DAILY_LIMIT_EXCEEDED);
        }
        account.consumeTicket();
        day.consumeTicket();
        return toDay(day, account.getAvailableTickets());
    }

    private RouletteAccount lockAccount(Long userId) {
        accountRepository.insertIfAbsent(userId);
        return accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("룰렛 계정을 찾을 수 없습니다."));
    }

    private RouletteParticipationDay initializeDay(Long userId, LocalDate date) {
        dayRepository.insertIfAbsent(userId, date);
        return dayRepository.findByUserIdAndDateForUpdate(userId, date)
                .orElseThrow(() -> new IllegalStateException("룰렛 일별 참여 정보를 찾을 수 없습니다."));
    }

    private RouletteDay toDay(RouletteParticipationDay day, long remaining) {
        return new RouletteDay(day.getParticipationDate(), day.getAttendanceGranted(), day.getGiftGranted(),
                day.getUsed(), remaining);
    }
}
