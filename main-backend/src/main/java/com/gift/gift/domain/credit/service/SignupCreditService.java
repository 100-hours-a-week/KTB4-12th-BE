package com.gift.gift.domain.credit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;

@Service
@RequiredArgsConstructor
public class SignupCreditService {

    private static final long REWARD_AMOUNT = 500_000L;
    private static final String LEDGER_KEY_PREFIX = "SIGNUP_REWARD:";

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    @Transactional
    public void grant(Long userId) {
        CreditAccount account = creditAccountRepository.saveAndFlush(
                new CreditAccount(userId, REWARD_AMOUNT)
        );

        CreditTransaction transaction = new CreditTransaction(
                userId,
                CreditTransactionType.SIGNUP_REWARD,
                REWARD_AMOUNT,
                account.getBalance(),
                null,
                null,
                null,
                ledgerKey(userId)
        );

        creditTransactionRepository.saveAndFlush(transaction);
    }

    private String ledgerKey(Long userId) {
        return LEDGER_KEY_PREFIX + userId;
    }
}
