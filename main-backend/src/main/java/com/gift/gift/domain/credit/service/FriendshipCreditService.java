package com.gift.gift.domain.credit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.friend.entity.Friendship;

@Service
@RequiredArgsConstructor
public class FriendshipCreditService {

    private static final long REWARD_AMOUNT = 100_000L;

    private static final String LEDGER_KEY_PREFIX = "FRIENDSHIP_REWARD:";

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    @Transactional
    public void grant(Friendship friendship) {
        if (friendship.getId() == null) {
            throw new IllegalStateException("저장되지 않은 친구 관계에는 크레딧을 지급할 수 없습니다.");
        }

        Long friendshipId = friendship.getId();
        Long senderId = friendship.getUser1().getId();
        Long recipientId = friendship.getUser2().getId();

        grant(friendshipId, senderId);
        grant(friendshipId, recipientId);
    }

    private void grant(Long friendshipId, Long userId) {

        int updated = creditAccountRepository.increaseBalance(userId, REWARD_AMOUNT);

        if (updated != 1) {
            throw new IllegalStateException("친구 성립 크레딧을 지급할 수 없습니다.");
        }

        CreditAccount account = creditAccountRepository
                .findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("사용자의 크레딧 계좌가 존재하지 않습니다."));

        CreditTransaction transaction =
                new CreditTransaction(
                        userId,
                        CreditTransactionType.FRIENDSHIP_REWARD,
                        REWARD_AMOUNT,
                        account.getBalance(),
                        null,
                        null,
                        null,
                        ledgerKey(friendshipId, userId)
                );

        creditTransactionRepository.saveAndFlush(transaction);
    }

    private String ledgerKey(Long friendshipId, Long userId) {

        return LEDGER_KEY_PREFIX
                + friendshipId
                + ":"
                + userId;
    }
}
