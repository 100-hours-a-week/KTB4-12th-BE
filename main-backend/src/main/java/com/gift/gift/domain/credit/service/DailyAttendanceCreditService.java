package com.gift.gift.domain.credit.service;

import java.time.Clock;
import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.roulette.service.RouletteService;
import com.gift.gift.domain.roulette.support.RouletteSettings;

@Service
@RequiredArgsConstructor
public class DailyAttendanceCreditService {

    private static final long REWARD_AMOUNT = 100_000L;

    private static final String LEDGER_KEY_PREFIX = "DAILY_ATTENDANCE_REWARD:";

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final Clock clock;
    private final RouletteService rouletteService;
    private final RouletteSettings rouletteSettings;

    @Transactional
    public void grant(Long userId) {
        if (rouletteSettings.enabled()) {
            rouletteService.attend(userId);
            return;
        }

        LocalDate attendanceDate = LocalDate.now(clock);

        String deduplicationKey = ledgerKey(userId, attendanceDate);

        creditAccountRepository
                .findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자의 크레딧 계좌가 존재하지 않습니다."));

        if (creditTransactionRepository.existsByDeduplicationKey(deduplicationKey)) {
            return;
        }

        int updatedCount = creditAccountRepository.increaseBalance(userId, REWARD_AMOUNT);

        if (updatedCount != 1) {
            throw new IllegalStateException("일별 접속 크레딧을 지급할 수 없습니다.");
        }

        CreditAccount account =
                creditAccountRepository
                        .findByUserId(userId)
                        .orElseThrow(() -> new IllegalStateException("사용자의 크레딧 계좌가 존재하지 않습니다."));

        CreditTransaction transaction =
                new CreditTransaction(
                        userId,
                        CreditTransactionType
                                .DAILY_ATTENDANCE_REWARD,
                        REWARD_AMOUNT,
                        account.getBalance(),
                        null,
                        null,
                        attendanceDate,
                        deduplicationKey
                );

        creditTransactionRepository.saveAndFlush(transaction);
    }

    private String ledgerKey(Long userId, LocalDate attendanceDate) {
        return LEDGER_KEY_PREFIX
                + userId
                + ":"
                + attendanceDate;
    }
}
